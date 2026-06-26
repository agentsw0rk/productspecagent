import { expect, test, type Page } from "@playwright/test";

const PROJECT_ID = "project-autosave-race";

function wizardData(productName: string) {
  return {
    projectId: PROJECT_ID,
    steps: {
      IDEA: {
        fields: {
          productName,
          category: "API",
          vision: "",
        },
        completedAt: null,
      },
    },
  };
}

async function mockWizardProject(page: Page) {
  await page.addInitScript(() => {
    window.sessionStorage.setItem("productspecagent.authToken", "test-token");
  });

  await page.route("**/api/v1/config/features", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({
      graphmeshEnabled: false,
      hackathonLock: {
        enabled: false,
        title: "",
        eventDate: "",
        targetAt: "",
        adminBypassEnabled: false,
      },
    }),
  }));

  await page.route("**/api/v1/auth/me", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ userId: "u1", email: "user@example.com", admin: false }),
  }));

  await page.route("**/api/v1/wizard-options", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ version: 1, categories: [], updatedAt: "2026-06-26T00:00:00Z" }),
  }));

  await page.route(`**/api/v1/projects/${PROJECT_ID}`, (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({
      project: {
        id: PROJECT_ID,
        name: "Autosave Race",
        ownerId: "u1",
        status: "DRAFT",
        createdAt: "2026-06-26T00:00:00Z",
        updatedAt: "2026-06-26T00:00:00Z",
        graphmeshEnabled: false,
      },
      flowState: {
        projectId: PROJECT_ID,
        currentStep: "IDEA",
        steps: [],
      },
    }),
  }));

  await page.route(`**/api/v1/projects/${PROJECT_ID}/flow`, (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ projectId: PROJECT_ID, currentStep: "IDEA", steps: [] }),
  }));

  await page.route(`**/api/v1/projects/${PROJECT_ID}/wizard/progression`, (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({
      category: "API",
      currentStep: "IDEA",
      status: "IN_PROGRESS",
      steps: [],
      primaryAction: { type: "COMPLETE_STEP", step: "IDEA" },
    }),
  }));

  await page.route(`**/api/v1/projects/${PROJECT_ID}/wizard`, (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify(wizardData("")),
  }));

  let saveCount = 0;
  await page.route(`**/api/v1/projects/${PROJECT_ID}/wizard/IDEA`, async (route) => {
    saveCount += 1;
    const currentSaveCount = saveCount;
    const requestBody = JSON.parse(route.request().postData() ?? "{}") as { fields?: { productName?: string } };
    await new Promise((resolve) => setTimeout(resolve, currentSaveCount === 1 ? 700 : 1000));
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(wizardData(currentSaveCount === 1 ? "SERVER_STALE" : requestBody.fields?.productName ?? "")),
    });
  });

  await page.route(`**/api/v1/projects/${PROJECT_ID}/**`, (route) => {
    const url = route.request().url();
    if (
      url.endsWith(`/api/v1/projects/${PROJECT_ID}`) ||
      url.endsWith(`/api/v1/projects/${PROJECT_ID}/flow`) ||
      url.endsWith(`/api/v1/projects/${PROJECT_ID}/wizard`) ||
      url.endsWith(`/api/v1/projects/${PROJECT_ID}/wizard/progression`)
    ) {
      return route.fallback();
    }
    if (route.request().method() === "GET") {
      return route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
    }
    return route.fallback();
  });
}

test.describe("wizard autosave", () => {
  test("keeps newer local input when an older autosave response returns", async ({ page }) => {
    await mockWizardProject(page);

    await page.goto(`/projects/${PROJECT_ID}`);

    const productName = page.getByPlaceholder("z.B. TaskFlow Pro");
    await expect(productName).toBeVisible();

    const firstSaveStarted = page.waitForRequest((request) =>
      request.method() === "PUT" && request.url().includes(`/api/v1/projects/${PROJECT_ID}/wizard/IDEA`),
    );
    const firstSaveResponse = page.waitForResponse((response) =>
      response.request().method() === "PUT" && response.url().includes(`/api/v1/projects/${PROJECT_ID}/wizard/IDEA`),
    );
    await productName.fill("A");
    await firstSaveStarted;
    await productName.pressSequentially("B");

    await firstSaveResponse;
    await page.waitForTimeout(50);
    expect(await productName.inputValue()).toBe("AB");
  });
});

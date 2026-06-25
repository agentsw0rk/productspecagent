import { expect, test, type Page } from "@playwright/test";

const API_BASE = process.env.TEST_API_URL ?? "http://localhost:8080";
const PROJECT_ID = "project-chat-disabled";

async function mockAuthenticatedProject(page: Page) {
  await page.addInitScript(() => {
    window.sessionStorage.setItem("productspecagent.authToken", "test-token");
  });

  await page.route(`${API_BASE}/api/v1/config/features`, async (route) => {
    await route.fulfill({
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
    });
  });

  await page.route(`${API_BASE}/api/v1/auth/me`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ userId: "u1", email: "user@example.com", admin: false }),
    });
  });

  await page.route(`${API_BASE}/api/v1/wizard-options`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ version: 1, categories: [], updatedAt: "2026-06-25T00:00:00Z" }),
    });
  });

  await page.route(`${API_BASE}/api/v1/projects/${PROJECT_ID}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        project: {
          id: PROJECT_ID,
          name: "Chat Disabled",
          ownerId: "u1",
          status: "DRAFT",
          createdAt: "2026-06-25T00:00:00Z",
          updatedAt: "2026-06-25T00:00:00Z",
          graphmeshEnabled: false,
        },
        flowState: {
          projectId: PROJECT_ID,
          currentStep: "IDEA",
          steps: [],
        },
      }),
    });
  });

  await page.route(`${API_BASE}/api/v1/projects/${PROJECT_ID}/flow`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ projectId: PROJECT_ID, currentStep: "IDEA", steps: [] }),
    });
  });

  await page.route(`${API_BASE}/api/v1/projects/${PROJECT_ID}/wizard`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        projectId: PROJECT_ID,
        steps: {
          IDEA: {
            fields: {
              productName: "Chat Disabled",
              category: "API",
              vision: "",
            },
            completedAt: null,
          },
        },
      }),
    });
  });

  await page.route(`${API_BASE}/api/v1/projects/${PROJECT_ID}/wizard/progression`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        category: "API",
        currentStep: "IDEA",
        status: "IN_PROGRESS",
        steps: [],
        primaryAction: { type: "COMPLETE_STEP", step: "IDEA" },
      }),
    });
  });

  await page.route(`${API_BASE}/api/v1/projects/${PROJECT_ID}/decisions`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });
  await page.route(`${API_BASE}/api/v1/projects/${PROJECT_ID}/clarifications`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });
  await page.route(`${API_BASE}/api/v1/projects/${PROJECT_ID}/tasks`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });
  await page.route(`${API_BASE}/api/v1/projects/${PROJECT_ID}/tasks/coverage`, async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "{}" });
  });
  await page.route(`${API_BASE}/api/v1/projects/${PROJECT_ID}/design/workbench`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ projectId: PROJECT_ID, description: "", designs: [] }),
    });
  });
}

test.describe("project chat panel", () => {
  test("disables manual agent chat for customers", async ({ page }) => {
    await mockAuthenticatedProject(page);

    await page.goto(`/projects/${PROJECT_ID}`);

    const composer = page.getByPlaceholder(/Nachrichten werden vom Wizard erzeugt/i);
    await expect(composer).toBeVisible();
    await expect(composer).toBeDisabled();
    await expect(page.getByRole("button", { name: /Manueller Chat ist deaktiviert/i })).toBeDisabled();
    await expect(page.getByText(/Der Wizard steuert den Agenten/i)).toBeVisible();
  });
});

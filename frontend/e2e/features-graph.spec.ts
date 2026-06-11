import { test, expect, type Page } from "@playwright/test";
import { seedSaaSProjectToFeatures } from "./fixtures/seed-project";

async function login(page: Parameters<typeof test>[0]["page"], email: string, password: string) {
  await page.goto("/login");
  await page.getByLabel(/^email$/i).fill(email);
  await page.getByLabel(/^passwort$/i).fill(password);
  await Promise.all([
    page.waitForURL(/\/projects(?:$|\/)/i),
    page.getByRole("button", { name: /^anmelden$/i }).click(),
  ]);
}

async function openFeaturesStep(page: Page) {
  await expect(page.getByRole("heading").first()).toBeVisible();
  await page.waitForLoadState("networkidle");

  const labeledStep = page.getByRole("button", { name: /^features$/i });
  if (await labeledStep.isVisible().catch(() => false)) {
    await labeledStep.click();
  } else {
    await page.locator('button[title="Features"]').click();
  }
  await expect(page.getByRole("heading", { name: /^Features$/ })).toBeVisible();
}

test.describe("Feature 22 — Features graph editor", () => {
  test("mount, add, rename, connect, reload — no console errors, stays responsive", async ({
    page,
    request,
  }) => {
    const { projectId, email, password } = await seedSaaSProjectToFeatures(request);

    await login(page, email, password);

    const consoleErrors: string[] = [];
    page.on("pageerror", (err) => consoleErrors.push(`pageerror: ${err.message}`));
    page.on("console", (msg) => {
      if (msg.type() === "error") consoleErrors.push(`console.error: ${msg.text()}`);
    });

    // Navigate to the project workspace page
    await page.goto(`/projects/${projectId}`);

    // Click the "Features" step in the StepIndicator — label is "Features"
    await openFeaturesStep(page);

    await expect(page.getByRole("button", { name: /^\+?\s*feature$/i }).first())
      .toBeVisible({ timeout: 8_000 });

    // Add first feature — button text is "+ Feature" (Plus icon + "Feature" text)
    await page.getByRole("button", { name: /^\+?\s*feature$/i }).first().click();

    const titleInput = page.getByLabel(/^Titel$/i);
    await expect(titleInput).toBeVisible({ timeout: 5_000 });
    await titleInput.fill("Authentication");
    await page.getByRole("button", { name: /^Speichern$/ }).click();
    await expect(page.getByRole("dialog", { name: /Feature bearbeiten/i })).toBeHidden();

    // The node label should update in the side panel immediately
    await expect(page.locator("text=Authentication")).toBeVisible({ timeout: 3_000 });

    // Add a second feature
    await page.getByRole("button", { name: /^\+?\s*feature$/i }).first().click();

    // Wait for debounced auto-save (800 ms debounce + buffer)
    await page.waitForTimeout(1_500);

    // Hard reload — both the graph and the "Authentication" feature must survive
    await page.reload();
    await openFeaturesStep(page);

    // The "Authentication" feature title must be visible somewhere in the UI
    // (either as a node label in the Rete canvas or in the side panel)
    await expect(page.locator("text=Authentication")).toBeVisible({ timeout: 10_000 });

    // Step indicator is still interactive — clicking around must not hang
    // (in the original React-19-Strict-Mode crash, the whole page froze)
    const ideaStep = page.getByRole("button", { name: /^idee$/i });
    if (await ideaStep.isVisible()) {
      await ideaStep.click();
      // Navigate back to features
      await openFeaturesStep(page);
    }

    // No React render-phase errors or unhandled JS errors must have occurred
    expect(
      consoleErrors,
      `Unexpected console errors:\n${consoleErrors.join("\n")}`,
    ).toEqual([]);
  });

  test("auto-layout persists updated feature coordinates immediately", async ({
    page,
    request,
  }) => {
    const { projectId, email, password } = await seedSaaSProjectToFeatures(request);

    const seedResponse = await request.put(
      `${process.env.TEST_API_URL ?? "http://localhost:8080"}/api/v1/projects/${projectId}/wizard/FEATURES`,
      {
        data: {
          fields: {
            features: [
              {
                id: "feature-a",
                title: "Authentication",
                scopes: ["FRONTEND"],
                description: "",
                scopeFields: {},
                acceptanceCriteria: [],
                position: { x: 0, y: 0 },
              },
              {
                id: "feature-b",
                title: "Dashboard",
                scopes: ["BACKEND"],
                description: "",
                scopeFields: {},
                acceptanceCriteria: [],
                position: { x: 0, y: 0 },
              },
            ],
            edges: [
              { id: "edge-a-b", from: "feature-a", to: "feature-b" },
            ],
          },
          completedAt: new Date().toISOString(),
        },
      },
    );
    expect(seedResponse.ok()).toBeTruthy();

    await login(page, email, password);
    await page.goto(`/projects/${projectId}`);
    await openFeaturesStep(page);

    await expect(page.locator('[data-testid="node"]')).toHaveCount(2, { timeout: 8_000 });

    const saveResponsePromise = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/v1/projects/${projectId}/wizard/FEATURES`) &&
        response.request().method() === "PUT",
    );

    await page.getByRole("button", { name: /auto-layout/i }).click();
    const saveResponse = await saveResponsePromise;
    expect(saveResponse.ok()).toBeTruthy();

    const wizardResponse = await request.get(
      `${process.env.TEST_API_URL ?? "http://localhost:8080"}/api/v1/projects/${projectId}/wizard`,
    );
    expect(wizardResponse.ok()).toBeTruthy();
    const wizard = await wizardResponse.json();
    const persistedFeatures = wizard.steps.FEATURES.fields.features as Array<{
      id: string;
      position: { x: number; y: number };
    }>;

    expect(persistedFeatures).toHaveLength(2);
    expect(
      persistedFeatures.some((feature) => feature.position.x !== 0 || feature.position.y !== 0),
    ).toBeTruthy();
  });

  test("dragging a line from output socket to input socket persists a feature dependency", async ({
    page,
    request,
  }) => {
    const { projectId, email, password } = await seedSaaSProjectToFeatures(request);

    const seedResponse = await request.put(
      `${process.env.TEST_API_URL ?? "http://localhost:8080"}/api/v1/projects/${projectId}/wizard/FEATURES`,
      {
        data: {
          fields: {
            features: [
              {
                id: "feature-a",
                title: "Authentication",
                scopes: ["FRONTEND"],
                description: "",
                scopeFields: {},
                acceptanceCriteria: [],
                position: { x: 80, y: 120 },
              },
              {
                id: "feature-b",
                title: "Dashboard",
                scopes: ["BACKEND"],
                description: "",
                scopeFields: {},
                acceptanceCriteria: [],
                position: { x: 420, y: 120 },
              },
            ],
            edges: [],
          },
          completedAt: new Date().toISOString(),
        },
      },
    );
    expect(seedResponse.ok()).toBeTruthy();

    await login(page, email, password);
    await page.goto(`/projects/${projectId}`);
    await openFeaturesStep(page);

    await expect(page.locator('[data-testid="node"]')).toHaveCount(2, { timeout: 8_000 });
    const outputSocket = page.locator(".output-socket").first();
    const inputSocket = page.locator(".input-socket").nth(1);
    await expect(outputSocket).toBeVisible();
    await expect(inputSocket).toBeVisible();

    const outputBox = await outputSocket.boundingBox();
    const inputBox = await inputSocket.boundingBox();
    expect(outputBox).not.toBeNull();
    expect(inputBox).not.toBeNull();
    expect(outputBox!.width).toBeGreaterThanOrEqual(24);
    expect(outputBox!.height).toBeGreaterThanOrEqual(24);
    expect(inputBox!.width).toBeGreaterThanOrEqual(24);
    expect(inputBox!.height).toBeGreaterThanOrEqual(24);

    await page.mouse.move(outputBox!.x + outputBox!.width / 2, outputBox!.y + outputBox!.height / 2);
    await page.mouse.down();
    await page.mouse.move(inputBox!.x + inputBox!.width / 2, inputBox!.y + inputBox!.height / 2, { steps: 12 });
    await page.mouse.up();

    await expect.poll(async () => {
      const wizardResponse = await request.get(
        `${process.env.TEST_API_URL ?? "http://localhost:8080"}/api/v1/projects/${projectId}/wizard`,
      );
      expect(wizardResponse.ok()).toBeTruthy();
      const wizard = await wizardResponse.json();
      return wizard.steps.FEATURES.fields.edges ?? [];
    }, { timeout: 3_000 }).toEqual([
      expect.objectContaining({ from: "feature-a", to: "feature-b" }),
    ]);

    await expect(page.locator('[data-testid="connection"]').first()).toBeVisible();
  });
});

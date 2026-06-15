import { expect, test } from "@playwright/test";

const API_BASE = process.env.TEST_API_URL ?? "http://localhost:8080";

async function mockAuthenticatedSession(page: Parameters<typeof test>[0]["page"]) {
  await page.addInitScript(() => {
    window.sessionStorage.setItem("productspecagent.authToken", "test-token");
  });
  await page.route(`${API_BASE}/api/v1/auth/me`, async (route) => {
    const auth = route.request().headers()["authorization"];
    if (auth !== "Bearer test-token") {
      await route.fulfill({ status: 401 });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ userId: "u1", email: "user@example.com", admin: false }),
    });
  });
}

async function mockForbidden(page: Parameters<typeof test>[0]["page"], path: string) {
  await page.route(`${API_BASE}${path}`, async (route) => {
    await route.fulfill({
      status: 403,
      contentType: "application/json",
      body: JSON.stringify({ message: "Forbidden" }),
    });
  });
}

test.describe("admin protected pages", () => {
  test("prompts page shows protected-area dialog for non-admin users", async ({ page }) => {
    await mockAuthenticatedSession(page);
    await mockForbidden(page, "/api/v1/prompts");

    await page.goto("/prompts");

    await expect(page.getByRole("dialog", { name: /Bereich geschützt/i })).toBeVisible();
    await expect(page.getByText(/Du hast keine Berechtigung/i)).toBeVisible();
  });

  test("agent models page shows protected-area dialog for non-admin users", async ({ page }) => {
    await mockAuthenticatedSession(page);
    await mockForbidden(page, "/api/v1/agent-models");

    await page.goto("/agent-models");

    await expect(page.getByRole("dialog", { name: /Bereich geschützt/i })).toBeVisible();
    await expect(page.getByText(/Du hast keine Berechtigung/i)).toBeVisible();
  });

  test("asset bundles page shows protected-area dialog for non-admin users", async ({ page }) => {
    await mockAuthenticatedSession(page);
    await mockForbidden(page, "/api/v1/asset-bundles");
    await page.route(`${API_BASE}/api/v1/wizard-options`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ version: 1, categories: [], updatedAt: "2026-06-10T00:00:00Z" }),
      });
    });

    await page.goto("/asset-bundles");

    await expect(page.getByRole("dialog", { name: /Bereich geschützt/i })).toBeVisible();
    await expect(page.getByText(/Du hast keine Berechtigung/i)).toBeVisible();
  });
});

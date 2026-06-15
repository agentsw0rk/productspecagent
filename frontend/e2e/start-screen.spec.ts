import { expect, test } from "@playwright/test";

const API_BASE = process.env.TEST_API_URL ?? "http://localhost:8080";

test.describe("public start screen", () => {
  test("shows the public start page without auth", async ({ page }) => {
    await page.goto("/");

    await expect(page.getByRole("heading", { name: /Product Spec Agent/i })).toBeVisible();
    await expect(page.getByText(/Von der Produktidee zur agententauglichen Spezifikation/i)).toBeVisible();
    await expect(page.getByRole("link", { name: /Anmelden/i })).toBeVisible();
  });

  test("skip intro stores preference and opens projects", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("button", { name: /Startseite ueberspringen/i }).click();

    await expect(page).toHaveURL(/\/login\?next=%2Fprojects$/);
    expect(await page.evaluate(() => window.localStorage.getItem("psa-start-screen-skipped"))).toBe("true");
  });

  test("keeps protected project routes behind login", async ({ page }) => {
    await page.goto("/projects");

    await expect(page).toHaveURL(/\/login\?next=%2Fprojects$/);
  });

  test("does not redirect authenticated users from the public start page back to itself", async ({ page }) => {
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

    await page.goto("/");

    await expect(page).toHaveURL("/");
    await expect(page.getByRole("heading", { name: /Product Spec Agent/i })).toBeVisible();
  });
});

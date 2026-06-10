import { expect, test } from "@playwright/test";

test.describe("public start screen", () => {
  test("shows the public start page without a session", async ({ page }) => {
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
});

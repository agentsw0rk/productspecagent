import { expect, test } from "@playwright/test";
import { API_BASE } from "@/lib/api";
import { getCountdownParts } from "@/lib/hackathon-countdown";

const featuresUrl = `${API_BASE}/api/v1/config/features`;
const authMeUrl = `${API_BASE}/api/v1/auth/me`;

function hackathonLockConfig(enabled: boolean) {
  return {
    graphmeshEnabled: false,
    hackathonLock: {
      enabled,
      title: "Hackathon CVDT",
      eventDate: "2026-06-26",
      targetAt: "2026-06-26T09:00:00+02:00",
      adminBypassEnabled: true,
    },
  };
}

test.describe("hackathon lock", () => {
  test("shows the countdown lock for guests while the lock is active", async ({ page }) => {
    await page.route(featuresUrl, (route) => route.fulfill({ json: hackathonLockConfig(true) }));
    await page.route(authMeUrl, (route) => route.fulfill({ status: 401 }));

    await page.goto("/");

    await expect(page.getByRole("heading", { name: "Hackathon CVDT" })).toBeVisible();
    await expect(page.getByText("Tage")).toBeVisible();
    await expect(page.getByText("Stunden")).toBeVisible();
    await expect(page.getByText("Minuten")).toBeVisible();
    await expect(page.getByText("Sekunden")).toBeVisible();
    await expect(page.getByRole("heading", { name: /Product Spec Agent/i })).not.toBeVisible();
  });

  test("shows the public start page when the lock is inactive", async ({ page }) => {
    await page.route(featuresUrl, (route) => route.fulfill({ json: hackathonLockConfig(false) }));
    await page.route(authMeUrl, (route) => route.fulfill({ status: 401 }));

    await page.goto("/");

    await expect(page.getByRole("heading", { name: /Product Spec Agent/i })).toBeVisible();
    await expect(page.getByText(/Von der Produktidee zur agententauglichen Spezifikation/i)).toBeVisible();
  });

  test("lets admins bypass an active lock", async ({ page }) => {
    await page.route(featuresUrl, (route) => route.fulfill({ json: hackathonLockConfig(true) }));
    await page.route(authMeUrl, (route) => route.fulfill({
      json: { userId: "admin-1", email: "admin@example.com", admin: true },
    }));

    await page.goto("/");

    await expect(page.getByRole("heading", { name: /Product Spec Agent/i })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Hackathon CVDT" })).not.toBeVisible();
  });

  test("lets guests open login while the lock is active", async ({ page }) => {
    await page.route(featuresUrl, (route) => route.fulfill({ json: hackathonLockConfig(true) }));
    await page.route(authMeUrl, (route) => route.fulfill({ status: 401 }));

    await page.goto("/login");

    await expect(page.getByRole("heading", { name: "Anmelden" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Hackathon CVDT" })).not.toBeVisible();
  });

  test("returns a complete zeroed countdown after the target time", () => {
    expect(getCountdownParts(
      "2026-06-26T09:00:00+02:00",
      new Date("2026-06-26T09:00:01+02:00")
    )).toEqual({
      days: 0,
      hours: 0,
      minutes: 0,
      seconds: 0,
      complete: true,
    });
  });
});

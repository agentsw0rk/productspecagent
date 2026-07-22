import { expect, test, type APIResponse, type Page } from "@playwright/test";
import { seedSaaSProjectToFeatures } from "./fixtures/seed-project";
import { commitFeatureTitle, createOkfRemote } from "./fixtures/okf-git-fixture";

const API = process.env.TEST_API_URL ?? "http://localhost:8080";
const AUTH_ID = "11111111-1111-4111-8111-111111111111";
const DASHBOARD_ID = "22222222-2222-4222-8222-222222222222";
const EDGE_ID = "33333333-3333-4333-8333-333333333333";

async function expectOk(response: APIResponse, label: string) {
  expect(response.ok(), `${label}: ${response.status()} ${await response.text()}`).toBeTruthy();
}

async function login(page: Page, email: string, password: string) {
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
  await expect(async () => {
    await page.locator('button[title="Features"]').click();
    await expect(page.getByRole("heading", { name: /^Features$/ })).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 10_000 });
  await expect(page.locator('[data-testid="node"]')).toHaveCount(2, { timeout: 10_000 });
  await page.waitForTimeout(250);
}

async function openOkfDialog(page: Page) {
  const button = page.getByRole("button", { name: "OKF-Synchronisation" });
  if (!(await button.isVisible().catch(() => false))) {
    await page.getByTitle("Explorer einblenden").click();
  }
  await expect(button).toBeVisible();
  await button.click();
  await expect(page.getByRole("dialog", { name: "OKF-Synchronisation" })).toBeVisible();
  return page.getByRole("dialog", { name: "OKF-Synchronisation" });
}

async function closeTopDialog(page: Page) {
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog").last()).toBeHidden();
}

test("migrates features, syncs external changes, and resolves a same-feature conflict", async ({
  page,
  request,
}) => {
  test.setTimeout(90_000);
  const remote = createOkfRemote();
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));

  try {
    const { projectId, email, password, token } = await seedSaaSProjectToFeatures(request);
    const headers = { Authorization: `Bearer ${token}` };

    const featureSeed = await request.put(`${API}/api/v1/projects/${projectId}/wizard/FEATURES`, {
      headers,
      data: {
        fields: {
          features: [
            {
              id: AUTH_ID,
              title: "Authentication",
              scopes: ["FRONTEND", "BACKEND"],
              description: "Users can sign in.",
              scopeFields: {},
              acceptanceCriteria: [],
              position: { x: 80, y: 120 },
            },
            {
              id: DASHBOARD_ID,
              title: "Dashboard",
              scopes: ["FRONTEND"],
              description: "Users can inspect their workspace.",
              scopeFields: {},
              acceptanceCriteria: [],
              position: { x: 420, y: 120 },
            },
          ],
          edges: [{ id: EDGE_ID, from: AUTH_ID, to: DASHBOARD_ID }],
        },
        completedAt: new Date().toISOString(),
      },
    });
    await expectOk(featureSeed, "seed features");

    await expectOk(await request.put(`${API}/api/v1/projects/${projectId}/okf/connection`, {
      headers,
      data: { remoteUrl: remote.remoteUrl, baseBranch: "main" },
    }), "connect OKF remote");

    const previewResponse = await request.post(`${API}/api/v1/projects/${projectId}/okf/migration/dry-run`, { headers });
    await expectOk(previewResponse, "migration dry-run");
    const preview = await previewResponse.json() as { issues: Array<{ severity: string }> };
    expect(preview.issues.filter((issue) => issue.severity === "ERROR")).toEqual([]);

    await expectOk(
      await request.post(`${API}/api/v1/projects/${projectId}/okf/migration/prepare`, { headers }),
      "prepare migration",
    );

    await login(page, email, password);
    await page.goto(`/projects/${projectId}`);
    await openFeaturesStep(page);
    let dialog = await openOkfDialog(page);
    await dialog.getByRole("button", { name: /Änderungen freigeben/i }).click();
    await expect(dialog.getByText("OKF ist aktiv", { exact: true })).toBeVisible({ timeout: 20_000 });
    await closeTopDialog(page);

    await page.reload();
    await openFeaturesStep(page);
    for (const title of ["Authentication", "Dashboard"]) {
      await page.locator('[data-testid="node"]').filter({ hasText: title }).dblclick();
      await expect(page.getByRole("dialog", { name: /Feature bearbeiten/i })).toBeVisible();
      await expect(page.getByLabel(/^Titel$/i)).toHaveValue(title);
      await closeTopDialog(page);
    }

    await page.getByTitle("Explorer einblenden").click();
    const docsTree = page.getByRole("button", { name: /^docs$/i }).locator("..");
    const okfTreeButton = docsTree.getByRole("button", { name: /^okf$/i });
    await okfTreeButton.click();
    const okfTree = okfTreeButton.locator("..");
    await okfTree.getByRole("button", { name: /^features$/i }).click();
    await okfTree.getByRole("button", { name: "authentication.md" }).click();
    await expect(page.getByText("docs/okf/features/authentication.md", { exact: true })).toBeVisible();
    await expect(page.locator("pre").last()).toContainText("Authentication");
    await closeTopDialog(page);
    await okfTree.getByRole("button", { name: "dashboard.md" }).click();
    await expect(page.getByText("docs/okf/features/dashboard.md", { exact: true })).toBeVisible();
    await expect(page.locator("pre").last()).toContainText("Dashboard");
    await closeTopDialog(page);

    const external = remote.cloneCanonical();
    commitFeatureTitle(external, "Dashboard", "Remote dashboard", "test: update dashboard externally");

    dialog = await openOkfDialog(page);
    const syncResponse = page.waitForResponse((response) =>
      response.url().endsWith(`/api/v1/projects/${projectId}/okf/sync`) &&
      response.request().method() === "POST",
    );
    const syncWizardReload = page.waitForResponse((response) =>
      response.url().endsWith(`/api/v1/projects/${projectId}/wizard`) &&
      response.request().method() === "GET",
    );
    await dialog.getByRole("button", { name: /^Synchronisieren$/i }).click();
    await expectOk(await syncResponse, "sync external OKF changes");
    await expectOk(await syncWizardReload, "reload wizard after OKF sync");
    await closeTopDialog(page);
    await openFeaturesStep(page);
    await expect(page.locator('[data-testid="node"]').filter({ hasText: "Remote dashboard" })).toBeVisible({ timeout: 20_000 });

    await page.locator('[data-testid="node"]').filter({ hasText: "Authentication" }).dblclick();
    await page.getByLabel(/^Titel$/i).fill("Local authentication");
    await page.getByRole("button", { name: /^Speichern$/ }).click();
    await expect(page.getByRole("button", { name: /Aenderungen pruefen/i })).toBeVisible({ timeout: 10_000 });

    commitFeatureTitle(external, "Authentication", "External authentication", "test: conflict on authentication");

    await page.getByRole("button", { name: /Aenderungen pruefen/i }).click();
    dialog = page.getByRole("dialog", { name: "OKF-Synchronisation" });
    await dialog.getByRole("button", { name: /Änderungen freigeben/i }).click();
    await expect(dialog.getByText("Konflikt lösen", { exact: true })).toBeVisible({ timeout: 20_000 });
    await expect(dialog.getByText("Basis", { exact: true })).toBeVisible();
    await expect(dialog.getByText("Remote", { exact: true })).toBeVisible();
    await expect(dialog.getByText("Vorschlag", { exact: true })).toBeVisible();
    await expect(dialog.locator("pre")).toContainText(["Authentication", "External authentication", "Local authentication"]);

    await dialog.getByRole("button", { name: /Remote übernehmen/i }).click();
    await expect(dialog.getByText("Konflikt lösen", { exact: true })).toBeHidden({ timeout: 20_000 });
    await expect(dialog.getByText("Änderungen prüfen", { exact: true })).toBeVisible();
    const finalApprovalResponse = page.waitForResponse((response) =>
      response.url().includes(`/api/v1/projects/${projectId}/okf/changesets/`) &&
      response.url().endsWith("/approve") &&
      response.request().method() === "POST",
    );
    const approvalWizardReload = page.waitForResponse((response) =>
      response.url().endsWith(`/api/v1/projects/${projectId}/wizard`) &&
      response.request().method() === "GET",
    );
    await dialog.getByRole("button", { name: /Änderungen freigeben/i }).click();
    await expectOk(await finalApprovalResponse, "approve rebased OKF changes");
    await expectOk(await approvalWizardReload, "reload wizard after OKF approval");
    await expect(dialog.getByText("OKF ist aktiv", { exact: true })).toBeVisible({ timeout: 20_000 });
    await closeTopDialog(page);
    await openFeaturesStep(page);
    await expect(page.locator('[data-testid="node"]').filter({ hasText: "External authentication" })).toBeVisible();

    const statusResponse = await request.get(`${API}/api/v1/projects/${projectId}/okf/status`, { headers });
    await expectOk(statusResponse, "final OKF status");
    const status = await statusResponse.json() as { pending: unknown; conflicts: unknown[] };
    expect(status.pending).toBeNull();
    expect(status.conflicts).toEqual([]);
    expect(pageErrors).toEqual([]);
  } finally {
    remote.cleanup();
  }
});

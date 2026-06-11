import { expect, test } from "@playwright/test";
import {
  appendWizardFeature,
  createDefaultWizardFeature,
  toCockpitFeatures,
} from "@/lib/cockpit-feature-data";
import type { LivingSyncSummary, WizardData } from "@/lib/api";

test.describe("Feature 53 - cockpit feature data", () => {
  test("maps wizard features and edges to unknown cockpit features", () => {
    const wizardData: WizardData = {
      projectId: "project-1",
      steps: {
        FEATURES: {
          completedAt: "2026-06-09T10:00:00Z",
          fields: {
            features: [
              {
                id: "auth",
                title: "Authentication",
                scopes: ["FRONTEND", "BACKEND"],
                description: "Users can sign in.",
                scopeFields: { api: "JWT session" },
                acceptanceCriteria: [{ id: "auth-ac-1", text: "Login succeeds" }],
                position: { x: 120, y: 40 },
              },
              {
                id: "dashboard",
                title: "Dashboard",
                scopes: ["FRONTEND"],
                description: "Users see project progress.",
                scopeFields: {},
                acceptanceCriteria: [],
                position: { x: 420, y: 80 },
              },
            ],
            edges: [{ id: "auth-dashboard", from: "auth", to: "dashboard" }],
          },
        },
      },
    };

    expect(toCockpitFeatures(wizardData)).toEqual([
      {
        id: "auth",
        title: "Authentication",
        summary: "Users can sign in.",
        status: "UNKNOWN",
        owner: "Product Owner",
        tests: { command: "Noch nicht angebunden", passed: 0, failed: 0, updatedAt: "unknown" },
        designs: 0,
        handoff: 0,
        updatedAt: "unknown",
        evidence: [],
        reviewItems: [{ id: "auth-ac-1", text: "Login succeeds", resolved: false }],
        scopes: ["FRONTEND", "BACKEND"],
        graphPosition: { x: 120, y: 40 },
        dependsOn: [],
      },
      {
        id: "dashboard",
        title: "Dashboard",
        summary: "Users see project progress.",
        status: "UNKNOWN",
        owner: "Product Owner",
        tests: { command: "Noch nicht angebunden", passed: 0, failed: 0, updatedAt: "unknown" },
        designs: 0,
        handoff: 0,
        updatedAt: "unknown",
        evidence: [],
        reviewItems: [{ id: "dashboard-review", text: "Done-Kriterien noch nicht angebunden", resolved: false }],
        scopes: ["FRONTEND"],
        graphPosition: { x: 420, y: 80 },
        dependsOn: ["auth"],
      },
    ]);
  });

  test("returns an empty list when the wizard has no features", () => {
    expect(toCockpitFeatures({ projectId: "project-1", steps: {} })).toEqual([]);
  });

  test("merges Living Sync reporter data into cockpit features", () => {
    const wizardData: WizardData = {
      projectId: "project-1",
      steps: {
        FEATURES: {
          completedAt: "2026-06-09T10:00:00Z",
          fields: {
            features: [
              {
                id: "auth",
                title: "Authentication",
                scopes: ["FRONTEND", "BACKEND"],
                description: "Users can sign in.",
                scopeFields: {},
                acceptanceCriteria: [{ id: "auth-ac-1", text: "Login succeeds" }],
                position: { x: 120, y: 40 },
              },
            ],
            edges: [],
          },
        },
      },
    };
    const livingSync: LivingSyncSummary = {
      projectId: "project-1",
      features: [
        {
          featureId: "auth",
          status: "IN_PROGRESS",
          summary: "Reporter saw implementation start.",
          updatedAt: "2026-06-10T10:00:00Z",
        },
      ],
      featureCompletions: [
        {
          projectId: "project-1",
          featureId: "auth",
          derivedStatus: "DONE",
          summary: "Authentication is implemented.",
          implementedItems: ["JWT login endpoint"],
          deviations: ["OAuth was deferred"],
          openPoints: ["Confirm token rotation policy"],
          technicalDebt: ["Session cleanup job is manual"],
          tests: [{ name: "AuthFlowTest", status: "PRESENT" }],
          warnings: ["Header title differed"],
          sourceEventId: "event-import",
          sourceFileName: "45-living-sync-mcp-done.md",
          updatedAt: "2026-06-10T11:00:00Z",
        },
      ],
      tests: {
        totalRuns: 1,
        passed: 34,
        failed: 0,
        lastStatus: "PASSED",
        lastCommand: "./gradlew test",
        updatedAt: "2026-06-10T11:05:00Z",
      },
      tokens: { inputTokens: 1000, outputTokens: 500, totalTokens: 1500 },
      changedFiles: ["backend/src/main/kotlin/Auth.kt"],
      commits: ["abc1234"],
      notes: [],
      recentEvents: [
        {
          id: "event-test",
          projectId: "project-1",
          type: "TEST_RUN",
          featureId: "auth",
          taskId: null,
          agentName: "living-sync-reporter",
          model: null,
          status: "PASSED",
          summary: "Backend tests passed.",
          evidence: ["AuthFlowTest"],
          files: [],
          commits: [],
          testCommand: "./gradlew test",
          testsPassed: 34,
          testsFailed: 0,
          inputTokens: null,
          outputTokens: null,
          totalTokens: null,
          createdAt: "2026-06-10T11:05:00Z",
        },
        {
          id: "event-code",
          projectId: "project-1",
          type: "CODE_CHANGES",
          featureId: "auth",
          taskId: null,
          agentName: "living-sync-reporter",
          model: null,
          status: null,
          summary: "Auth service changed.",
          evidence: [],
          files: ["backend/src/main/kotlin/Auth.kt"],
          commits: ["abc1234"],
          testCommand: null,
          testsPassed: null,
          testsFailed: null,
          inputTokens: null,
          outputTokens: null,
          totalTokens: null,
          createdAt: "2026-06-10T11:04:00Z",
        },
      ],
      updatedAt: "2026-06-10T11:05:00Z",
    };

    const [feature] = toCockpitFeatures(wizardData, livingSync);

    expect(feature).toMatchObject({
      id: "auth",
      title: "Authentication",
      summary: "Authentication is implemented.",
      status: "DONE",
      tests: { command: "./gradlew test", passed: 34, failed: 0, updatedAt: "2026-06-10T11:05:00Z" },
      updatedAt: "2026-06-10T11:00:00Z",
    });
    expect(feature.evidence).toEqual(expect.arrayContaining([
      "JWT login endpoint",
      "AuthFlowTest",
      "backend/src/main/kotlin/Auth.kt",
      "Commit abc1234",
    ]));
    expect(feature.reviewItems.map((item) => item.text)).toEqual(expect.arrayContaining([
      "Confirm token rotation policy",
      "OAuth was deferred",
      "Session cleanup job is manual",
      "Header title differed",
    ]));
    expect(feature.handoff).toBeGreaterThan(0);
  });

  test("creates wizard feature defaults and appends without dropping edges", () => {
    const wizardData: WizardData = {
      projectId: "project-1",
      steps: {
        IDEA: {
          completedAt: "2026-06-09T09:00:00Z",
          fields: { category: "SaaS" },
        },
        FEATURES: {
          completedAt: "2026-06-09T10:00:00Z",
          fields: {
            features: [
              {
                id: "auth",
                title: "Authentication",
                scopes: ["BACKEND"],
                description: "Users can sign in.",
                scopeFields: {},
                acceptanceCriteria: [],
                position: { x: 0, y: 0 },
              },
            ],
            edges: [{ id: "auth-dashboard", from: "auth", to: "dashboard" }],
          },
        },
      },
    };
    const created = createDefaultWizardFeature(["FRONTEND", "BACKEND"], { x: 280, y: 160 });
    const nextStep = appendWizardFeature(wizardData, {
      ...created,
      id: "dashboard",
      title: "Dashboard",
      description: "Users see project progress.",
      scopeFields: { screens: "/dashboard" },
      acceptanceCriteria: [{ id: "dashboard-ac-1", text: "Dashboard opens" }],
    });

    expect(created.scopes).toEqual(["FRONTEND"]);
    expect(created.position).toEqual({ x: 280, y: 160 });
    expect(nextStep.completedAt).toBe("2026-06-09T10:00:00Z");
    expect(nextStep.fields.edges).toEqual([{ id: "auth-dashboard", from: "auth", to: "dashboard" }]);
    expect(nextStep.fields.features).toEqual([
      wizardData.steps.FEATURES.fields.features[0],
      {
        ...created,
        id: "dashboard",
        title: "Dashboard",
        description: "Users see project progress.",
        scopeFields: { screens: "/dashboard" },
        acceptanceCriteria: [{ id: "dashboard-ac-1", text: "Dashboard opens" }],
      },
    ]);
  });
});

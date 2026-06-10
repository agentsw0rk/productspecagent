import { expect, test } from "@playwright/test";
import {
  appendWizardFeature,
  createDefaultWizardFeature,
  toCockpitFeatures,
} from "@/lib/cockpit-feature-data";
import type { WizardData } from "@/lib/api";

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

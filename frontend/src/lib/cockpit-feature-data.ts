import type { FeatureScope, GraphPosition, WizardData, WizardFeature, WizardFeatureEdge } from "@/lib/api";

export type CockpitFeatureStatus = "UNKNOWN" | "DONE" | "IN_PROGRESS" | "BLOCKED" | "PLANNED";

export interface TestRun {
  command: string;
  passed: number;
  failed: number;
  updatedAt: string;
}

export interface ReviewItem {
  id: string;
  text: string;
  resolved: boolean;
}

export interface CockpitFeature {
  id: string;
  title: string;
  summary: string;
  status: CockpitFeatureStatus;
  owner: string;
  tests: TestRun;
  designs: number;
  handoff: number;
  updatedAt: string;
  evidence: string[];
  reviewItems: ReviewItem[];
  scopes: FeatureScope[];
  graphPosition: GraphPosition;
  dependsOn: string[];
}

const UNKNOWN_TESTS: TestRun = {
  command: "Noch nicht angebunden",
  passed: 0,
  failed: 0,
  updatedAt: "unknown",
};

export function toCockpitFeatures(wizardData: WizardData): CockpitFeature[] {
  const fields = wizardData.steps.FEATURES?.fields ?? {};
  const features = Array.isArray(fields.features) ? (fields.features as WizardFeature[]) : [];
  const edges = Array.isArray(fields.edges) ? (fields.edges as WizardFeatureEdge[]) : [];
  const incomingByTarget = edges.reduce<Record<string, string[]>>((result, edge) => {
    result[edge.to] = [...(result[edge.to] ?? []), edge.from];
    return result;
  }, {});

  return features.map((feature) => ({
    id: feature.id,
    title: feature.title,
    summary: feature.description,
    status: "UNKNOWN",
    owner: "Product Owner",
    tests: UNKNOWN_TESTS,
    designs: 0,
    handoff: 0,
    updatedAt: "unknown",
    evidence: [],
    reviewItems: feature.acceptanceCriteria.length > 0
      ? feature.acceptanceCriteria.map((criterion) => ({
          id: criterion.id,
          text: criterion.text,
          resolved: false,
        }))
      : [{ id: `${feature.id}-review`, text: "Done-Kriterien noch nicht angebunden", resolved: false }],
    scopes: feature.scopes,
    graphPosition: feature.position,
    dependsOn: incomingByTarget[feature.id] ?? [],
  }));
}

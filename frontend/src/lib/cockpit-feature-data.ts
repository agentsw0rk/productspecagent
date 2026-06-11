import type {
  FeatureCompletionSnapshot,
  FeatureScope,
  GraphPosition,
  LivingSyncEvent,
  LivingSyncFeatureStatus,
  LivingSyncSummary,
  WizardData,
  WizardFeature,
  WizardFeatureEdge,
  WizardStepData,
} from "@/lib/api";

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

export function toCockpitFeatures(
  wizardData: WizardData,
  livingSync?: LivingSyncSummary | null,
): CockpitFeature[] {
  const fields = wizardData.steps.FEATURES?.fields ?? {};
  const features = Array.isArray(fields.features) ? (fields.features as WizardFeature[]) : [];
  const edges = Array.isArray(fields.edges) ? (fields.edges as WizardFeatureEdge[]) : [];
  const incomingByTarget = edges.reduce<Record<string, string[]>>((result, edge) => {
    result[edge.to] = [...(result[edge.to] ?? []), edge.from];
    return result;
  }, {});

  return features.map((feature) => {
    const completion = latestByUpdatedAt(
      (livingSync?.featureCompletions ?? []).filter((snapshot) => snapshot.featureId === feature.id),
    );
    const progress = latestByUpdatedAt(
      (livingSync?.features ?? []).filter((syncFeature) => syncFeature.featureId === feature.id),
    );
    const events = featureEvents(livingSync, feature.id);
    const status = toCockpitStatus(completion?.derivedStatus ?? progress?.status);
    const tests = toCockpitTests(completion, events);
    const reviewItems = toReviewItems(feature, completion, events);
    const evidence = toEvidence(completion, events);

    return {
      id: feature.id,
      title: feature.title,
      summary: completion?.summary || progress?.summary || feature.description,
      status,
      owner: events.length > 0 ? "Living Sync Reporter" : "Product Owner",
      tests,
      designs: 0,
      handoff: toHandoffScore(status, tests, evidence, reviewItems),
      updatedAt: completion?.updatedAt ?? progress?.updatedAt ?? "unknown",
      evidence,
      reviewItems,
      scopes: feature.scopes,
      graphPosition: feature.position,
      dependsOn: incomingByTarget[feature.id] ?? [],
    };
  });
}

function latestByUpdatedAt<T extends { updatedAt: string }>(items: T[]): T | undefined {
  return [...items].sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))[0];
}

function latestEventByCreatedAt(events: LivingSyncEvent[], type: LivingSyncEvent["type"]): LivingSyncEvent | undefined {
  return [...events]
    .filter((event) => event.type === type)
    .sort((left, right) => right.createdAt.localeCompare(left.createdAt))[0];
}

function featureEvents(summary: LivingSyncSummary | null | undefined, featureId: string): LivingSyncEvent[] {
  return summary?.recentEvents.filter((event) => event.featureId === featureId) ?? [];
}

function toCockpitStatus(status?: LivingSyncFeatureStatus): CockpitFeatureStatus {
  return status ?? "UNKNOWN";
}

function toCockpitTests(
  completion: FeatureCompletionSnapshot | undefined,
  events: LivingSyncEvent[],
): TestRun {
  const latestTestRun = latestEventByCreatedAt(events, "TEST_RUN");
  if (latestTestRun) {
    return {
      command: latestTestRun.testCommand ?? "Living Sync Testlauf",
      passed: latestTestRun.testsPassed ?? 0,
      failed: latestTestRun.testsFailed ?? 0,
      updatedAt: latestTestRun.createdAt,
    };
  }

  if (completion?.tests.length) {
    const failed = completion.tests.filter((test) => isFailedTestStatus(test.status)).length;
    return {
      command: completion.sourceFileName,
      passed: completion.tests.length - failed,
      failed,
      updatedAt: completion.updatedAt,
    };
  }

  return UNKNOWN_TESTS;
}

function isFailedTestStatus(status: string): boolean {
  return /fail|failed|error/i.test(status);
}

function toEvidence(
  completion: FeatureCompletionSnapshot | undefined,
  events: LivingSyncEvent[],
): string[] {
  const evidence = [
    ...(completion?.implementedItems ?? []),
    ...(completion?.tests.map((test) => test.name) ?? []),
    ...events.flatMap((event) => event.evidence),
    ...events.flatMap((event) => event.files),
    ...events.flatMap((event) => event.commits.map((commit) => `Commit ${commit}`)),
    ...events
      .filter((event) => event.totalTokens !== null)
      .map((event) => `Tokens ${event.totalTokens?.toLocaleString()}`),
  ];
  return uniqueNonEmpty(evidence);
}

function toReviewItems(
  feature: WizardFeature,
  completion: FeatureCompletionSnapshot | undefined,
  events: LivingSyncEvent[],
): ReviewItem[] {
  const items = [
    ...(completion?.openPoints ?? []),
    ...(completion?.deviations ?? []),
    ...(completion?.technicalDebt ?? []),
    ...(completion?.warnings ?? []),
    ...events
      .filter((event) => event.type === "SYNC_NOTE" || event.status === "BLOCKED")
      .map((event) => event.summary),
  ];

  const uniqueItems = uniqueNonEmpty(items);
  if (uniqueItems.length > 0) {
    return uniqueItems.map((text, index) => ({
      id: `${feature.id}-sync-review-${index}`,
      text,
      resolved: false,
    }));
  }

  return feature.acceptanceCriteria.length > 0
    ? feature.acceptanceCriteria.map((criterion) => ({
        id: criterion.id,
        text: criterion.text,
        resolved: false,
      }))
    : [{ id: `${feature.id}-review`, text: "Done-Kriterien noch nicht angebunden", resolved: false }];
}

function toHandoffScore(
  status: CockpitFeatureStatus,
  tests: TestRun,
  evidence: string[],
  reviewItems: ReviewItem[],
): number {
  if (status === "UNKNOWN" && tests.command === UNKNOWN_TESTS.command && evidence.length === 0) {
    return 0;
  }

  let score = 20;
  if (status === "DONE") score += 35;
  if (status === "IN_PROGRESS") score += 20;
  if (status === "BLOCKED") score -= 10;
  if (tests.command !== UNKNOWN_TESTS.command) score += tests.failed === 0 ? 20 : 5;
  if (evidence.length > 0) score += 15;
  score -= Math.min(30, reviewItems.filter((item) => !item.resolved).length * 5);
  return Math.max(0, Math.min(100, score));
}

function uniqueNonEmpty(items: string[]): string[] {
  return Array.from(new Set(items.map((item) => item.trim()).filter(Boolean)));
}

export function wizardFeaturesFromData(wizardData: WizardData): WizardFeature[] {
  const fields = wizardData.steps.FEATURES?.fields ?? {};
  return Array.isArray(fields.features) ? (fields.features as WizardFeature[]) : [];
}

export function wizardEdgesFromData(wizardData: WizardData): WizardFeatureEdge[] {
  const fields = wizardData.steps.FEATURES?.fields ?? {};
  return Array.isArray(fields.edges) ? (fields.edges as WizardFeatureEdge[]) : [];
}

export function createDefaultWizardFeature(
  allowedScopes: FeatureScope[],
  position: GraphPosition,
): WizardFeature {
  return {
    id: crypto.randomUUID(),
    title: "Neues Feature",
    description: "",
    scopes: allowedScopes.slice(0, 1),
    scopeFields: {},
    acceptanceCriteria: [],
    position,
  };
}

export function appendWizardFeature(wizardData: WizardData, feature: WizardFeature): WizardStepData {
  const features = wizardFeaturesFromData(wizardData);
  const edges = wizardEdgesFromData(wizardData);
  const existingStep = wizardData.steps.FEATURES;

  return {
    completedAt: existingStep?.completedAt ?? null,
    fields: {
      ...(existingStep?.fields ?? {}),
      features: [...features, feature],
      edges,
    },
  };
}

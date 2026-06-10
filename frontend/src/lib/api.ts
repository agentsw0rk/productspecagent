import type { Category } from "@/lib/category-step-config";

const PRODUCTION_API_BASE = "https://productspecagent-backend.hackathon.netrtl.com";

export const API_BASE = process.env.NEXT_PUBLIC_API_URL
  || (process.env.NODE_ENV === "production" ? PRODUCTION_API_BASE : "http://localhost:8080");

let onUnauthorized: (() => void) | null = null;

export function setUnauthorizedHandler(fn: (() => void) | null) {
  onUnauthorized = fn;
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: unknown,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

function apiErrorMessage(body: unknown): string | null {
  if (!body || typeof body !== "object") return null;
  const fields = ["message", "detail", "error"] as const;
  for (const field of fields) {
    const value = (body as Record<string, unknown>)[field];
    if (typeof value === "string" && value.trim()) {
      return value;
    }
  }
  return null;
}

interface ApiFetchOptions extends RequestInit {
  suppressUnauthorizedHandler?: boolean;
}

export async function apiFetch<T>(
  path: string,
  options?: ApiFetchOptions
): Promise<T> {
  const { suppressUnauthorizedHandler = false, ...requestOptions } = options ?? {};
  const res = await fetch(`${API_BASE}${path}`, {
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...requestOptions.headers,
    },
    ...requestOptions,
  });

  if (res.status === 401 && !suppressUnauthorizedHandler) {
    onUnauthorized?.();
  }

  if (!res.ok) {
    const body = await res.json().catch(() => null);
    const bodyMessage = apiErrorMessage(body);
    throw new ApiError(res.status, body, bodyMessage || `API error: ${res.status}`);
  }

  if (res.status === 204 || res.headers.get("content-length") === "0") {
    return undefined as T;
  }
  return res.json() as Promise<T>;
}

// ─── Domain Types ────────────────────────────────────────────────────────────

export type StepType = "IDEA" | "PROBLEM" | "FEATURES" | "MVP" | "DESIGN"
  | "ARCHITECTURE" | "BACKEND" | "FRONTEND" | "REVIEW";
export type StepStatus = "OPEN" | "IN_PROGRESS" | "COMPLETED";

export interface WizardOption {
  id: string;
  label: string;
  enabled: boolean;
}

export interface WizardOptionField {
  step: StepType;
  key: string;
  label: string;
  options: WizardOption[];
}

export interface WizardOptionCategory {
  id: Category;
  label: string;
  visibleSteps: StepType[];
  allowedScopes: FeatureScope[];
  fields: WizardOptionField[];
}

export interface WizardOptionCatalog {
  version: number;
  categories: WizardOptionCategory[];
  updatedAt: string;
}

// ─── Feature Graph Types ──────────────────────────────────────────────────────

export type FeatureScope = "FRONTEND" | "BACKEND";

export interface GraphPosition { x: number; y: number }

export interface AcceptanceCriterion {
  id: string;
  text: string;
}

export interface WizardFeature {
  id: string;
  title: string;
  scopes: FeatureScope[];
  description: string;
  scopeFields: Record<string, string>;
  acceptanceCriteria: AcceptanceCriterion[];
  position: GraphPosition;
}

export interface WizardFeatureEdge {
  id: string;
  from: string;
  to: string;
}

export interface WizardFeatureGraph {
  features: WizardFeature[];
  edges: WizardFeatureEdge[];
}
export type ProjectStatus = "DRAFT" | "IN_PROGRESS" | "COMPLETED";

export interface FlowStep {
  stepType: StepType;
  status: StepStatus;
  updatedAt: string;
}

export interface FlowState {
  projectId: string;
  steps: FlowStep[];
  currentStep: StepType;
}

export interface Project {
  id: string;
  name: string;
  ownerId: string;
  status: ProjectStatus;
  createdAt: string;
  updatedAt: string;
  graphmeshEnabled?: boolean;
}

export interface ProjectResponse {
  project: Project;
  flowState: FlowState;
}

export interface CreateProjectRequest {
  name: string;
}

export interface ChatRequest {
  message: string;
  locale: string;
}

export interface ChatResponse {
  message: string;
  flowStateChanged: boolean;
  currentStep: string;
  decisionId: string | null;
  clarificationId: string | null;
}

// ─── Clarification Types ─────────────────────────────────────────────────────

export type ClarificationStatus = "OPEN" | "ANSWERED";

export interface Clarification {
  id: string;
  projectId: string;
  stepType: StepType;
  question: string;
  reason: string;
  status: ClarificationStatus;
  answer: string | null;
  createdAt: string;
  answeredAt: string | null;
}

export interface AnswerClarificationRequest {
  answer: string;
}

// ─── Decision Types ──────────────────────────────────────────────────────────

export type DecisionStatus = "PENDING" | "RESOLVED";

export interface DecisionOption {
  id: string;
  label: string;
  pros: string[];
  cons: string[];
  recommended: boolean;
}

export interface Decision {
  id: string;
  projectId: string;
  stepType: StepType;
  title: string;
  options: DecisionOption[];
  recommendation: string;
  status: DecisionStatus;
  chosenOptionId: string | null;
  rationale: string | null;
  createdAt: string;
  resolvedAt: string | null;
}

export interface CreateDecisionRequest {
  title: string;
  stepType: StepType;
}

export interface ResolveDecisionRequest {
  chosenOptionId: string;
  rationale: string;
}

// ─── Check Types ─────────────────────────────────────────────────────────────

export type CheckSeverity = "ERROR" | "WARNING" | "INFO";

export interface CheckResult {
  id: string;
  severity: CheckSeverity;
  category: string;
  message: string;
  relatedArtifact: string | null;
  suggestedFix: string | null;
}

export interface CheckSummary {
  errors: number;
  warnings: number;
  infos: number;
  passed: boolean;
}

export interface CheckReport {
  projectId: string;
  results: CheckResult[];
  checkedAt: string;
  summary: CheckSummary;
}

// ─── Task Types ──────────────────────────────────────────────────────────────

export type TaskType = "EPIC" | "STORY" | "TASK";
export type TaskItemStatus = "TODO" | "IN_PROGRESS" | "DONE";

export interface SpecTask {
  id: string;
  projectId: string;
  parentId: string | null;
  type: TaskType;
  title: string;
  description: string;
  estimate: string;
  priority: number;
  status: TaskItemStatus;
  specSection: StepType | null;
  dependencies: string[];
  createdAt: string;
  updatedAt: string;
}

export interface UpdateTaskRequest {
  title?: string;
  description?: string;
  estimate?: string;
  priority?: number;
  status?: TaskItemStatus;
  parentId?: string;
  dependencies?: string[];
}

export interface CoverageMap {
  [key: string]: boolean;
}

// ─── File Explorer Types ─────────────────────────────────────────────────────

export interface FileEntry {
  name: string;
  path: string;
  isDirectory: boolean;
  size: number;
  children?: FileEntry[];
}

export interface FileContent {
  path: string;
  name: string;
  content: string;
  language: string;
  lineCount: number;
  binary?: boolean;
}

// ─── Handoff Types ──────────────────────────────────────────────────────────

export interface HandoffPreview {
  claudeMd: string;
  agentsMd: string;
  implementationOrder: string;
  format: string;
  syncUrl: string;
}

export interface HandoffExportRequest {
  claudeMd?: string;
  agentsMd?: string;
  implementationOrder?: string;
  syncUrl?: string;
}

// ─── Living Sync Types ──────────────────────────────────────────────────────

export type LivingSyncFeatureStatus = "PLANNED" | "IN_PROGRESS" | "BLOCKED" | "DONE";
export type LivingSyncEventType = "FEATURE_PROGRESS" | "FEATURE_DONE_IMPORT" | "TEST_RUN" | "TOKEN_USAGE" | "CODE_CHANGES" | "SYNC_NOTE";

export interface LivingSyncEvent {
  id: string;
  projectId: string;
  type: LivingSyncEventType;
  featureId: string | null;
  taskId: string | null;
  agentName: string | null;
  model: string | null;
  status: string | null;
  summary: string;
  evidence: string[];
  files: string[];
  commits: string[];
  testCommand: string | null;
  testsPassed: number | null;
  testsFailed: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  totalTokens: number | null;
  createdAt: string;
}

export interface LivingSyncFeatureSummary {
  featureId: string;
  status: LivingSyncFeatureStatus;
  summary: string;
  updatedAt: string;
}

export interface FeatureCompletionTestEvidence {
  name: string;
  status: string;
}

export interface FeatureCompletionSnapshot {
  projectId: string;
  featureId: string;
  derivedStatus: LivingSyncFeatureStatus;
  summary: string;
  implementedItems: string[];
  deviations: string[];
  openPoints: string[];
  technicalDebt: string[];
  tests: FeatureCompletionTestEvidence[];
  warnings: string[];
  sourceEventId: string;
  sourceFileName: string;
  updatedAt: string;
}

export interface LivingSyncTestSummary {
  totalRuns: number;
  passed: number;
  failed: number;
  lastStatus: string | null;
  lastCommand: string | null;
  updatedAt: string | null;
}

export interface LivingSyncTokenSummary {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
}

export interface LivingSyncSummary {
  projectId: string;
  features: LivingSyncFeatureSummary[];
  featureCompletions: FeatureCompletionSnapshot[];
  tests: LivingSyncTestSummary;
  tokens: LivingSyncTokenSummary;
  changedFiles: string[];
  commits: string[];
  notes: LivingSyncEvent[];
  recentEvents: LivingSyncEvent[];
  updatedAt: string | null;
}

// ─── API Endpoints ──────────────────────────────────────────────────────────

export async function getHealth(): Promise<{ status: string; timestamp: string }> {
  return apiFetch("/api/health");
}

export async function listProjects(): Promise<Project[]> {
  return apiFetch<Project[]>("/api/v1/projects");
}

export async function createProject(data: CreateProjectRequest): Promise<ProjectResponse> {
  return apiFetch<ProjectResponse>("/api/v1/projects", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function getProject(id: string): Promise<ProjectResponse> {
  return apiFetch<ProjectResponse>(`/api/v1/projects/${id}`);
}

export async function deleteProject(id: string): Promise<void> {
  const res = await fetch(`${API_BASE}/api/v1/projects/${id}`, { method: "DELETE", credentials: "include" });
  if (res.status === 401) onUnauthorized?.();
  if (!res.ok) {
    const error = await res.json().catch(() => ({ message: res.statusText }));
    throw new Error(error.message || `API error: ${res.status}`);
  }
}

export async function getFlowState(projectId: string): Promise<FlowState> {
  return apiFetch<FlowState>(`/api/v1/projects/${projectId}/flow`);
}

export async function sendChatMessage(projectId: string, data: ChatRequest): Promise<ChatResponse> {
  return apiFetch<ChatResponse>(`/api/v1/projects/${projectId}/agent/chat`, {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function listDecisions(projectId: string): Promise<Decision[]> {
  return apiFetch<Decision[]>(`/api/v1/projects/${projectId}/decisions`);
}

export async function createDecision(projectId: string, data: CreateDecisionRequest): Promise<Decision> {
  return apiFetch<Decision>(`/api/v1/projects/${projectId}/decisions`, {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function getDecision(projectId: string, decisionId: string): Promise<Decision> {
  return apiFetch<Decision>(`/api/v1/projects/${projectId}/decisions/${decisionId}`);
}

export async function resolveDecision(projectId: string, decisionId: string, data: ResolveDecisionRequest): Promise<Decision> {
  return apiFetch<Decision>(`/api/v1/projects/${projectId}/decisions/${decisionId}/resolve`, {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function listClarifications(projectId: string): Promise<Clarification[]> {
  return apiFetch<Clarification[]>(`/api/v1/projects/${projectId}/clarifications`);
}

export async function getClarification(projectId: string, clarificationId: string): Promise<Clarification> {
  return apiFetch<Clarification>(`/api/v1/projects/${projectId}/clarifications/${clarificationId}`);
}

export async function proposeFeatures(projectId: string): Promise<WizardFeatureGraph> {
  return apiFetch<WizardFeatureGraph>(`/api/v1/projects/${projectId}/features/propose`, {
    method: "POST",
  });
}

export async function proposeAcceptanceCriteria(
  projectId: string,
  featureId: string,
): Promise<AcceptanceCriterion[]> {
  return apiFetch<AcceptanceCriterion[]>(
    `/api/v1/projects/${projectId}/features/${featureId}/acceptance-criteria/propose`,
    { method: "POST" },
  );
}

export async function answerClarification(projectId: string, clarificationId: string, data: AnswerClarificationRequest): Promise<Clarification> {
  return apiFetch<Clarification>(`/api/v1/projects/${projectId}/clarifications/${clarificationId}/answer`, {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function listTasks(projectId: string): Promise<SpecTask[]> {
  return apiFetch<SpecTask[]>(`/api/v1/projects/${projectId}/tasks`);
}

export async function generatePlan(projectId: string): Promise<SpecTask[]> {
  return apiFetch<SpecTask[]>(`/api/v1/projects/${projectId}/tasks/generate`, { method: "POST" });
}

export async function getTask(projectId: string, taskId: string): Promise<SpecTask> {
  return apiFetch<SpecTask>(`/api/v1/projects/${projectId}/tasks/${taskId}`);
}

export async function updateTask(projectId: string, taskId: string, data: UpdateTaskRequest): Promise<SpecTask> {
  return apiFetch<SpecTask>(`/api/v1/projects/${projectId}/tasks/${taskId}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export async function deleteTask(projectId: string, taskId: string): Promise<void> {
  const res = await fetch(`${API_BASE}/api/v1/projects/${projectId}/tasks/${taskId}`, { method: "DELETE", credentials: "include" });
  if (res.status === 401) onUnauthorized?.();
}

export async function getTaskCoverage(projectId: string): Promise<CoverageMap> {
  return apiFetch<CoverageMap>(`/api/v1/projects/${projectId}/tasks/coverage`);
}

export async function runChecks(projectId: string): Promise<CheckReport> {
  return apiFetch<CheckReport>(`/api/v1/projects/${projectId}/checks`, { method: "POST" });
}

export async function getCheckResults(projectId: string): Promise<CheckReport> {
  return apiFetch<CheckReport>(`/api/v1/projects/${projectId}/checks/results`);
}

export async function getHandoffPreview(projectId: string): Promise<HandoffPreview> {
  return apiFetch<HandoffPreview>(`/api/v1/projects/${projectId}/handoff/preview`, { method: "POST" });
}

export async function getLivingSyncSummary(projectId: string): Promise<LivingSyncSummary> {
  return apiFetch<LivingSyncSummary>(`/api/v1/projects/${projectId}/living-sync`);
}

export async function exportHandoff(projectId: string, request: HandoffExportRequest = {}): Promise<Blob> {
  const res = await fetch(`${API_BASE}/api/v1/projects/${projectId}/handoff/export`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  if (res.status === 401) onUnauthorized?.();
  if (!res.ok) throw new Error("Handoff export failed");
  return res.blob();
}

// ─── Wizard Types ─────────────────────────────────────────────────────────────

export interface WizardStepData {
  fields: Record<string, unknown>;
  completedAt: string | null;
}

export interface WizardData {
  projectId: string;
  steps: Record<string, WizardStepData>;
}

export interface WizardStepView {
  step: StepType;
  status: StepStatus;
  visible: boolean;
  finalVisibleStep: boolean;
}

export interface WizardPrimaryAction {
  type: "COMPLETE_STEP" | "OPEN_EXPORT" | "NONE";
  step?: StepType | null;
}

export interface WizardClientAction {
  type: "SHOW_STEP" | "OPEN_EXPORT" | "STAY";
  step?: StepType | null;
}

export interface WizardCreatedArtifacts {
  decisionIds: string[];
  clarificationIds: string[];
}

export interface WizardProgressionView {
  category: string | null;
  steps: WizardStepView[];
  currentStep: StepType | null;
  status: string;
  primaryAction: WizardPrimaryAction;
}

// ─── Wizard API ───────────────────────────────────────────────────────────────

export async function getWizardData(projectId: string): Promise<WizardData> {
  return apiFetch<WizardData>(`/api/v1/projects/${projectId}/wizard`);
}

export async function getWizardProgression(projectId: string): Promise<WizardProgressionView> {
  return apiFetch<WizardProgressionView>(`/api/v1/projects/${projectId}/wizard/progression`);
}

export async function getWizardOptions(): Promise<WizardOptionCatalog> {
  return apiFetch<WizardOptionCatalog>("/api/v1/wizard-options");
}

export async function getAdminWizardOptions(): Promise<WizardOptionCatalog> {
  return apiFetch<WizardOptionCatalog>("/api/v1/admin/wizard-options");
}

export async function saveAdminWizardOptions(catalog: WizardOptionCatalog): Promise<WizardOptionCatalog> {
  return apiFetch<WizardOptionCatalog>("/api/v1/admin/wizard-options", {
    method: "PUT",
    body: JSON.stringify(catalog),
  });
}

export async function resetAdminWizardOptions(): Promise<WizardOptionCatalog> {
  return apiFetch<WizardOptionCatalog>("/api/v1/admin/wizard-options/reset", { method: "POST" });
}

export async function saveWizardData(projectId: string, data: WizardData): Promise<WizardData> {
  return apiFetch<WizardData>(`/api/v1/projects/${projectId}/wizard`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export async function saveWizardStep(projectId: string, step: string, data: WizardStepData): Promise<WizardData> {
  return apiFetch<WizardData>(`/api/v1/projects/${projectId}/wizard/${step}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export async function listProjectFiles(projectId: string): Promise<FileEntry[]> {
  return apiFetch<FileEntry[]>(`/api/v1/projects/${projectId}/files`);
}

export async function readProjectFile(projectId: string, filePath: string): Promise<FileContent> {
  return apiFetch<FileContent>(`/api/v1/projects/${projectId}/files/${filePath}`);
}

export async function exportProject(
  projectId: string,
  options: {
    includeTasks?: boolean;
    includeDesign?: boolean;
  } = {}
): Promise<Blob> {
  const res = await fetch(`${API_BASE}/api/v1/projects/${projectId}/export`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      includeTasks: options.includeTasks ?? true,
      includeDesign: options.includeDesign ?? true,
    }),
  });
  if (res.status === 401) onUnauthorized?.();
  if (!res.ok) throw new Error("Export failed");
  return res.blob();
}

// ─── Feature flags ───────────────────────────────────────────────────────────

export interface FeatureFlags {
  graphmeshEnabled: boolean;
}

export async function getFeatures(): Promise<FeatureFlags> {
  return apiFetch<FeatureFlags>("/api/v1/config/features");
}

export async function setProjectGraphMeshEnabled(projectId: string, enabled: boolean): Promise<Project> {
  return apiFetch<Project>(`/api/v1/projects/${projectId}/graphmesh-enabled`, {
    method: "PATCH",
    body: JSON.stringify({ enabled }),
  });
}

// ─── Wizard Chat Types ───────────────────────────────────────────────────────

export interface WizardStepCompleteRequest {
  step: string;
  fields: Record<string, unknown>;
  locale: string;
}

export interface WizardStepCompleteResponse {
  message: string;
  nextStep: string | null;
  exportTriggered: boolean;
  decisionId?: string | null;
  clarificationId?: string | null;
  progression?: WizardProgressionView;
  action?: WizardClientAction;
  artifacts?: WizardCreatedArtifacts;
  appliedDecisionIds?: string[];
  appliedClarificationIds?: string[];
  wizardDataChanged?: boolean;
}

export async function completeWizardStep(
  projectId: string,
  data: WizardStepCompleteRequest
): Promise<WizardStepCompleteResponse> {
  return apiFetch<WizardStepCompleteResponse>(
    `/api/v1/projects/${projectId}/agent/wizard-step-complete`,
    { method: "POST", body: JSON.stringify(data) }
  );
}

// ------- Documents -------

export type DocumentState = "UPLOADED" | "PROCESSING" | "EXTRACTED" | "FAILED" | "LOCAL";

export interface ProjectDocument {
  id: string;
  title: string;
  mimeType: string;
  state: DocumentState;
  createdAt: string;
}

export async function uploadDocument(projectId: string, file: File): Promise<ProjectDocument> {
  const form = new FormData();
  form.append("file", file);
  const res = await fetch(`${API_BASE}/api/v1/projects/${projectId}/documents`, {
    method: "POST",
    credentials: "include",
    body: form,
  });
  if (res.status === 401) onUnauthorized?.();
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error ?? `Upload failed: ${res.status}`);
  }
  return res.json();
}

export async function listDocuments(projectId: string): Promise<ProjectDocument[]> {
  return apiFetch<ProjectDocument[]>(`/api/v1/projects/${projectId}/documents`);
}

export async function deleteDocument(projectId: string, documentId: string): Promise<void> {
  const res = await fetch(`${API_BASE}/api/v1/projects/${projectId}/documents/${documentId}`, {
    method: "DELETE",
    credentials: "include",
  });
  if (res.status === 401) onUnauthorized?.();
  if (!res.ok) throw new Error(`Delete failed: ${res.status}`);
}

// ─── Asset-Bundle Types ──────────────────────────────────────────────────────

export interface AssetBundleManifest {
  id: string;
  scope: "MATCHED" | "GLOBAL";
  step: StepType | null;
  field: string | null;
  value: string | null;
  version: string;
  title: string;
  description: string;
  createdAt: string;
  updatedAt: string;
}

export interface AssetBundleFile {
  relativePath: string;
  size: number;
  contentType: string;
}

export interface AssetBundleListItem {
  id: string;
  scope: "MATCHED" | "GLOBAL";
  step: StepType | null;
  field: string | null;
  value: string | null;
  version: string;
  title: string;
  description: string;
  fileCount: number;
}

export interface AssetBundleDetail {
  manifest: AssetBundleManifest;
  files: AssetBundleFile[];
}

export interface AssetBundleUploadResult {
  manifest: AssetBundleManifest;
  fileCount: number;
}

// ─── Asset-Bundle API ────────────────────────────────────────────────────────

export async function listAssetBundles(): Promise<AssetBundleListItem[]> {
  return apiFetch<AssetBundleListItem[]>("/api/v1/asset-bundles");
}

export async function getAssetBundle(
  step: StepType,
  field: string,
  value: string,
): Promise<AssetBundleDetail> {
  const path = `/api/v1/asset-bundles/${step}/${field}/${encodeURIComponent(value)}`;
  return apiFetch<AssetBundleDetail>(path);
}

export async function getAssetBundleById(id: string): Promise<AssetBundleDetail> {
  return apiFetch<AssetBundleDetail>(`/api/v1/asset-bundles/by-id/${encodeURIComponent(id)}`);
}

export async function uploadAssetBundle(file: File): Promise<AssetBundleUploadResult> {
  const form = new FormData();
  form.append("file", file);
  const res = await fetch(`${API_BASE}/api/v1/asset-bundles`, {
    method: "POST",
    credentials: "include",
    body: form,
  });
  if (res.status === 401) onUnauthorized?.();
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message ?? body.error ?? `Upload failed: ${res.status}`);
  }
  return res.json();
}

export async function deleteAssetBundle(
  step: StepType,
  field: string,
  value: string,
): Promise<void> {
  const path = `/api/v1/asset-bundles/${step}/${field}/${encodeURIComponent(value)}`;
  const res = await fetch(`${API_BASE}${path}`, { method: "DELETE", credentials: "include" });
  if (res.status === 401) onUnauthorized?.();
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message ?? body.error ?? `Delete failed: ${res.status}`);
  }
}

export async function deleteAssetBundleById(id: string): Promise<void> {
  const path = `/api/v1/asset-bundles/by-id/${encodeURIComponent(id)}`;
  const res = await fetch(`${API_BASE}${path}`, { method: "DELETE", credentials: "include" });
  if (res.status === 401) onUnauthorized?.();
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message ?? body.error ?? `Delete failed: ${res.status}`);
  }
}

export async function fetchAssetBundleFile(
  step: StepType,
  field: string,
  value: string,
  relativePath: string,
): Promise<Response> {
  const encodedPath = relativePath.split("/").map(encodeURIComponent).join("/");
  const path = `/api/v1/asset-bundles/${step}/${field}/${encodeURIComponent(value)}/files/${encodedPath}`;
  return fetch(`${API_BASE}${path}`, { credentials: "include" });
}

export async function fetchAssetBundleFileById(id: string, relativePath: string): Promise<Response> {
  const encodedPath = relativePath.split("/").map(encodeURIComponent).join("/");
  const path = `/api/v1/asset-bundles/by-id/${encodeURIComponent(id)}/files/${encodedPath}`;
  return fetch(`${API_BASE}${path}`, { credentials: "include" });
}

// ─── Prompts ─────────────────────────────────────────────────────────────────

export interface PromptListItem {
  id: string;
  title: string;
  description: string;
  agent: string;
  isOverridden: boolean;
}

export interface PromptDetail extends PromptListItem {
  content: string;
}

export interface PromptValidationError {
  errors: string[];
}

export async function listPrompts(): Promise<PromptListItem[]> {
  return apiFetch<PromptListItem[]>("/api/v1/prompts");
}

export async function getPrompt(id: string): Promise<PromptDetail> {
  return apiFetch<PromptDetail>(`/api/v1/prompts/${encodeURIComponent(id)}`);
}

export async function savePrompt(id: string, content: string): Promise<void> {
  return apiFetch<void>(`/api/v1/prompts/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify({ content }),
  });
}

export async function resetPrompt(id: string): Promise<void> {
  return apiFetch<void>(`/api/v1/prompts/${encodeURIComponent(id)}`, {
    method: "DELETE",
  });
}

// Agent Models

export type AgentModelTier = "SMALL" | "MEDIUM" | "LARGE";

export interface AgentModelInfo {
  agentId: string;
  displayName: string;
  defaultTier: AgentModelTier;
  currentTier: AgentModelTier;
  isOverridden: boolean;
  tierMapping: Record<AgentModelTier, string>;
}

export async function listAgentModels(): Promise<AgentModelInfo[]> {
  return apiFetch<AgentModelInfo[]>("/api/v1/agent-models");
}

export async function updateAgentModel(agentId: string, tier: AgentModelTier): Promise<void> {
  await apiFetch<void>(`/api/v1/agent-models/${encodeURIComponent(agentId)}`, {
    method: "PUT",
    body: JSON.stringify({ tier }),
  });
}

export async function resetAgentModel(agentId: string): Promise<void> {
  await apiFetch<void>(`/api/v1/agent-models/${encodeURIComponent(agentId)}`, { method: "DELETE" });
}

// ─── Design Workbench Types ──────────────────────────────────────────────────

export interface DesignImageInput {
  originalName: string;
  contentRef: string;
  contentType: string;
  sizeBytes: number;
  uploadedAt: string;
}

export interface DesignColor {
  hex: string;
  role: string;
  weight: string;
  notes: string;
}

export interface DesignTypographySignal {
  category: string;
  role: string;
  weight: string;
  notes: string;
}

export interface DesignLayoutRegion {
  name: string;
  order: number;
  priority: number;
  description: string;
}

export interface DesignComponentSignal {
  name: string;
  role: string;
  description: string;
}

export interface DesignImageAnalysis {
  summary: string;
  palette: DesignColor[];
  typography: DesignTypographySignal[];
  layoutHierarchy: DesignLayoutRegion[];
  components: DesignComponentSignal[];
  moodTags: string[];
  brandSignals: string[];
  designBrief: string;
}

export interface DesignAnalysis {
  summary: string;
  visualDirection: string;
  rationale: string;
}

export interface GeneratedDesign {
  id: string;
  title: string;
  htmlPath: string;
  rationale: string;
  createdAt: string;
}

export interface DesignWorkbench {
  projectId: string;
  description?: string | null;
  imageInput?: DesignImageInput | null;
  imageAnalysis?: DesignImageAnalysis | null;
  imageAnalysisError?: string | null;
  analysis?: DesignAnalysis | null;
  currentDesign?: GeneratedDesign | null;
  updatedAt: string;
}

export interface DesignCompleteResponse {
  message: string;
  nextStep?: string | null;
  progression?: WizardProgressionView | null;
  action?: WizardClientAction | null;
}

// ─── Design Bundle Types ─────────────────────────────────────────────────────

export interface DesignPage {
  id: string;
  label: string;
  sectionId: string;
  sectionTitle: string;
  width: number;
  height: number;
}

export interface DesignBundleFile {
  path: string;
  sizeBytes: number;
  mimeType: string;
}

export interface DesignBundle {
  projectId: string;
  originalFilename: string;
  uploadedAt: string;
  sizeBytes: number;
  entryHtml: string;
  pages: DesignPage[];
  files: DesignBundleFile[];
  entryUrl: string;
  bundleUrl: string;
}

// ─── Design Bundle API ───────────────────────────────────────────────────────

export async function uploadDesignBundle(projectId: string, file: File): Promise<DesignBundle> {
  const fd = new FormData();
  fd.append("file", file);
  const res = await fetch(`${API_BASE}/api/v1/projects/${encodeURIComponent(projectId)}/design/upload`, {
    method: "POST",
    credentials: "include",
    body: fd,
  });
  if (res.status === 401) onUnauthorized?.();
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Upload failed (${res.status})`);
  }
  return (await res.json()) as DesignBundle;
}

export async function getDesignBundle(projectId: string): Promise<DesignBundle | null> {
  const res = await fetch(`${API_BASE}/api/v1/projects/${encodeURIComponent(projectId)}/design`, { credentials: "include" });
  if (res.status === 401) onUnauthorized?.();
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`Failed to load design bundle (${res.status})`);
  return (await res.json()) as DesignBundle;
}

export async function deleteDesignBundle(projectId: string): Promise<void> {
  const res = await fetch(`${API_BASE}/api/v1/projects/${encodeURIComponent(projectId)}/design`, {
    method: "DELETE",
    credentials: "include",
  });
  if (res.status === 401) onUnauthorized?.();
  if (!res.ok && res.status !== 404) {
    throw new Error(`Failed to delete design bundle (${res.status})`);
  }
}

// ─── Design Workbench API ────────────────────────────────────────────────────

export async function getDesignWorkbench(projectId: string): Promise<DesignWorkbench> {
  return apiFetch<DesignWorkbench>(`/api/v1/projects/${encodeURIComponent(projectId)}/design/workbench`);
}

export async function saveDesignInput(
  projectId: string,
  description: string,
  file?: File | null,
): Promise<DesignWorkbench> {
  const fd = new FormData();
  const trimmedDescription = description.trim();
  if (trimmedDescription) {
    fd.append("description", trimmedDescription);
  }
  if (file) {
    fd.append("file", file);
  }

  const res = await fetch(`${API_BASE}/api/v1/projects/${encodeURIComponent(projectId)}/design/input`, {
    method: "PUT",
    credentials: "include",
    body: fd,
  });
  if (res.status === 401) onUnauthorized?.();
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    const bodyMessage = apiErrorMessage(body);
    throw new ApiError(res.status, body, bodyMessage || `API error: ${res.status}`);
  }
  return (await res.json()) as DesignWorkbench;
}

export async function generateDesign(projectId: string): Promise<DesignWorkbench> {
  return apiFetch<DesignWorkbench>(`/api/v1/projects/${encodeURIComponent(projectId)}/design/generate`, { method: "POST" });
}

export async function analyzeDesignImage(projectId: string): Promise<DesignWorkbench> {
  return apiFetch<DesignWorkbench>(`/api/v1/projects/${encodeURIComponent(projectId)}/design/image/analyze`, { method: "POST" });
}

export function designPreviewUrl(projectId: string): string {
  return `${API_BASE}/api/v1/projects/${encodeURIComponent(projectId)}/design/preview`;
}

export async function completeDesignWorkbench(projectId: string): Promise<DesignCompleteResponse> {
  return apiFetch<DesignCompleteResponse>(`/api/v1/projects/${encodeURIComponent(projectId)}/design/complete`, { method: "POST" });
}

// ─── Auth ────────────────────────────────────────────────────────────────────

export interface AuthMe {
  userId: string;
  email: string;
}

export async function authRegister(email: string, password: string): Promise<AuthMe> {
  return apiFetch<AuthMe>("/api/v1/auth/register", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });
}

export async function authLogin(email: string, password: string): Promise<AuthMe> {
  return apiFetch<AuthMe>("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });
}

export async function authLogout(): Promise<void> {
  await apiFetch<void>("/api/v1/auth/logout", { method: "POST" });
}

export async function authMe(): Promise<AuthMe> {
  return apiFetch<AuthMe>("/api/v1/auth/me", { suppressUnauthorizedHandler: true });
}

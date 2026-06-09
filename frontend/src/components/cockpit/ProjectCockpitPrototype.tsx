"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRete } from "rete-react-plugin";
import {
  AlertTriangle,
  ArrowLeft,
  Check,
  CheckCircle2,
  Circle,
  FileCode2,
  GitBranch,
  LayoutTemplate,
  Plus,
  RefreshCw,
  TestTube2,
  XCircle,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Progress } from "@/components/ui/progress";
import { createFeaturesEditor, type FeaturesEditorContext } from "@/components/wizard/steps/features/editor";
import type { FeatureScope, WizardFeature, WizardFeatureEdge } from "@/lib/api";
import type { CockpitFeature, CockpitFeatureStatus } from "@/lib/cockpit-feature-data";
import { cn } from "@/lib/utils";

const STATUS_LABEL: Record<CockpitFeatureStatus, string> = {
  UNKNOWN: "Unknown",
  DONE: "Done",
  IN_PROGRESS: "In Arbeit",
  BLOCKED: "Blockiert",
  PLANNED: "Geplant",
};

const STATUS_BADGE: Record<CockpitFeatureStatus, "success" | "default" | "destructive" | "ghost"> = {
  UNKNOWN: "ghost",
  DONE: "success",
  IN_PROGRESS: "default",
  BLOCKED: "destructive",
  PLANNED: "ghost",
};

interface ProjectCockpitPrototypeProps {
  projectId: string;
  initialFeatures: CockpitFeature[];
}

export function ProjectCockpitPrototype({ projectId, initialFeatures }: ProjectCockpitPrototypeProps) {
  const [features, setFeatures] = useState(initialFeatures);
  const [activeId, setActiveId] = useState(initialFeatures[0]?.id ?? "");
  const [adding, setAdding] = useState(false);
  const [newTitle, setNewTitle] = useState("");

  const activeFeature = features.find((feature) => feature.id === activeId) ?? features[0] ?? null;
  const stats = useMemo(() => {
    const done = features.filter((feature) => feature.status === "DONE").length;
    const failed = features.reduce((sum, feature) => sum + feature.tests.failed, 0);
    const open = features.reduce((sum, feature) => sum + feature.reviewItems.filter((item) => !item.resolved).length, 0);
    const designs = features.reduce((sum, feature) => sum + feature.designs, 0);
    return { done, failed, open, designs };
  }, [features]);

  function addFeature() {
    const title = newTitle.trim();
    if (!title) return;

    const id = `feature-${Date.now()}`;
    const nextFeature: CockpitFeature = {
      id,
      title,
      summary: "Neu erfasstes Feature. Als Naechstes Ziel, Akzeptanz und Designbedarf klaeren.",
      status: "UNKNOWN",
      owner: "Product Owner",
      tests: { command: "Noch nicht gelaufen", passed: 0, failed: 0, updatedAt: "neu" },
      designs: 0,
      handoff: 12,
      updatedAt: "Gerade eben",
      evidence: ["Feature im Cockpit erfasst"],
      scopes: ["FRONTEND"],
      graphPosition: { x: 0, y: Math.max(160, features.length * 140) },
      dependsOn: [],
      reviewItems: [{ id: `${id}-review`, text: "Ziel und Done-Kriterien klaeren", resolved: false }],
    };

    setFeatures((current) => [nextFeature, ...current]);
    setActiveId(id);
    setNewTitle("");
    setAdding(false);
  }

  function toggleReviewItem(itemId: string) {
    if (!activeFeature) return;

    setFeatures((current) =>
      current.map((feature) =>
        feature.id === activeFeature.id
          ? {
              ...feature,
              reviewItems: feature.reviewItems.map((item) =>
                item.id === itemId ? { ...item, resolved: !item.resolved } : item
              ),
            }
          : feature
      )
    );
  }

  function captureDesign() {
    if (!activeFeature) return;

    setFeatures((current) =>
      current.map((feature) =>
        feature.id === activeFeature.id
          ? { ...feature, designs: feature.designs + 1, updatedAt: "Gerade eben" }
          : feature
      )
    );
  }

  return (
    <div className="flex h-full min-h-screen flex-col overflow-hidden bg-background text-foreground">
      <header className="flex h-12 shrink-0 items-center justify-between gap-3 border-b border-border bg-background px-5">
        <div className="flex min-w-0 items-center gap-2 text-xs text-muted-foreground">
          <Link
            href={`/projects/${projectId}`}
            className="inline-flex items-center gap-1 rounded-md px-1.5 py-1 transition-colors hover:bg-secondary hover:text-foreground"
          >
            <ArrowLeft size={13} />
            Workspace
          </Link>
          <span className="opacity-50">/</span>
          <span className="truncate text-[13px] font-semibold text-foreground">Projekt-Cockpit</span>
          <Badge variant="ghost">Prototype</Badge>
        </div>

        <div className="flex shrink-0 items-center gap-2">
          <Button size="sm" onClick={captureDesign} disabled={!activeFeature} className="gap-1.5">
            <LayoutTemplate size={14} />
            Design erfassen
          </Button>
          <Button size="sm" onClick={() => setAdding(true)} className="gap-1.5">
            <Plus size={14} />
            Feature erfassen
          </Button>
        </div>
      </header>

      <main className="grid min-h-0 flex-1 grid-cols-1 overflow-hidden lg:grid-cols-[22rem_minmax(0,1fr)]">
        <aside className="flex min-h-0 flex-col border-b border-border bg-sidebar/40 lg:border-b-0 lg:border-r">
          <div className="border-b border-border px-4 py-4">
            <div className="grid grid-cols-4 gap-2">
              <Metric value={`${stats.done}/${features.length}`} label="Done" tone="success" />
              <Metric value={stats.failed.toString()} label="Fehler" tone={stats.failed > 0 ? "danger" : "muted"} />
              <Metric value={stats.open.toString()} label="Offen" tone={stats.open > 0 ? "warning" : "muted"} />
              <Metric value={stats.designs.toString()} label="Designs" tone="primary" />
            </div>
          </div>

          {adding && (
            <div className="border-b border-border bg-card/45 p-3">
              <label htmlFor="feature-title" className="mb-2 block text-xs font-medium text-muted-foreground">
                Neues Feature
              </label>
              <div className="flex gap-2">
                <Input
                  id="feature-title"
                  value={newTitle}
                  onChange={(event) => setNewTitle(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") addFeature();
                    if (event.key === "Escape") setAdding(false);
                  }}
                  placeholder="Feature-Titel"
                  autoFocus
                />
                <Button size="sm" onClick={addFeature}>Hinzufuegen</Button>
              </div>
            </div>
          )}

          <div className="min-h-0 flex-1 overflow-y-auto p-2">
            <div className="mb-2 flex items-center justify-between px-2">
              <h1 className="text-sm font-semibold">Features</h1>
              <span className="text-xs text-muted-foreground">{features.length} Eintraege</span>
            </div>
            <div className="space-y-1">
              {features.map((feature) => (
                <button
                  key={feature.id}
                  type="button"
                  onClick={() => setActiveId(feature.id)}
                  className={cn(
                    "w-full rounded-md border px-3 py-2.5 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                    activeFeature?.id === feature.id
                      ? "border-primary/35 bg-primary/10"
                      : "border-transparent hover:border-border hover:bg-secondary/50"
                  )}
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <div className="truncate text-sm font-medium">{feature.title}</div>
                      <div className="mt-1 flex items-center gap-2 text-[11px] text-muted-foreground">
                        <TestHealth failed={feature.tests.failed} />
                        <span>{feature.tests.updatedAt}</span>
                      </div>
                    </div>
                    <Badge variant={STATUS_BADGE[feature.status]}>{STATUS_LABEL[feature.status]}</Badge>
                  </div>
                  <div className="mt-2 flex items-center gap-2 text-[11px] text-muted-foreground">
                    <span>{feature.reviewItems.filter((item) => !item.resolved).length} offen</span>
                    <span className="opacity-50">/</span>
                    <span>{feature.designs} Designs</span>
                    <span className="opacity-50">/</span>
                    <span>{feature.handoff}% Handoff</span>
                  </div>
                </button>
              ))}
              {features.length === 0 && (
                <div className="rounded-md border border-dashed border-border px-3 py-6 text-center text-sm text-muted-foreground">
                  Keine Features im Wizard-State gefunden.
                </div>
              )}
            </div>
          </div>
        </aside>

        <section className="min-h-0 overflow-hidden">
          <div className="min-h-0 overflow-y-auto px-5 py-5">
            {activeFeature ? (
              <>
                <FeatureHeader feature={activeFeature} />

                <CockpitFeatureGraph
                  features={features}
                  activeId={activeFeature.id}
                  onSelectFeature={setActiveId}
                />

                <div className="mt-5 grid gap-4 2xl:grid-cols-[minmax(0,1fr)_20rem]">
                  <section className="rounded-md border border-border bg-card/45">
                    <div className="flex items-center justify-between border-b border-border px-4 py-3">
                      <div>
                        <h2 className="text-sm font-semibold">Snapshot-Review</h2>
                        <p className="mt-1 text-xs text-muted-foreground">Was ist per Done-Import belastbar, was bleibt fachlich offen?</p>
                      </div>
                      <Badge variant={activeFeature.status === "DONE" ? "success" : "ghost"}>
                        {activeFeature.status === "DONE" ? "Review bereit" : "Review unbekannt"}
                      </Badge>
                    </div>
                    <div className="divide-y divide-border/70">
                      {activeFeature.reviewItems.map((item) => (
                        <button
                          key={item.id}
                          type="button"
                          onClick={() => toggleReviewItem(item.id)}
                          className="flex w-full items-start gap-3 px-4 py-3 text-left transition-colors hover:bg-secondary/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                        >
                          <span
                            className={cn(
                              "mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full border",
                              item.resolved
                                ? "border-emerald-400/40 bg-emerald-400/10 text-emerald-300"
                                : "border-amber-400/45 bg-amber-400/10 text-amber-300"
                            )}
                          >
                            {item.resolved ? <Check size={12} /> : <Circle size={10} />}
                          </span>
                          <span className={cn("text-sm", item.resolved ? "text-muted-foreground line-through" : "text-foreground")}>
                            {item.text}
                          </span>
                        </button>
                      ))}
                    </div>
                  </section>

                  <section className="rounded-md border border-border bg-card/45 p-4">
                    <div className="flex items-center gap-2">
                      <GitBranch size={15} className="text-primary" />
                      <h2 className="text-sm font-semibold">Handoff-Reife</h2>
                    </div>
                    <div className="mt-4">
                      <div className="mb-2 flex items-center justify-between text-xs">
                        <span className="text-muted-foreground">Bereit fuer Agentenarbeit</span>
                        <span className="font-medium">{activeFeature.handoff}%</span>
                      </div>
                      <Progress value={activeFeature.handoff} />
                    </div>
                    <div className="mt-4 space-y-2 text-xs text-muted-foreground">
                      <div className="flex items-center gap-2"><AlertTriangle size={13} className="text-amber-400" /> Statusdaten noch nicht angebunden</div>
                      <div className="flex items-center gap-2"><AlertTriangle size={13} className="text-amber-400" /> Tests noch nicht angebunden</div>
                      <div className="flex items-center gap-2"><AlertTriangle size={13} className="text-amber-400" /> Offene Review-Punkte klaeren</div>
                    </div>
                  </section>
                </div>

                <section className="mt-4 rounded-md border border-border bg-card/45">
                  <div className="flex items-center justify-between border-b border-border px-4 py-3">
                    <div>
                      <h2 className="text-sm font-semibold">Testlaeufe und Evidence</h2>
                      <p className="mt-1 text-xs text-muted-foreground">Letzte gemeldete Pruefung aus Living Sync oder importiertem Completion-Snapshot.</p>
                    </div>
                    <Button size="xs" variant="ghost" className="gap-1.5">
                      <RefreshCw size={12} />
                      Aktualisieren
                    </Button>
                  </div>
                  <div className="grid gap-3 p-4 md:grid-cols-[18rem_minmax(0,1fr)]">
                    <div className="rounded-md border border-border/70 bg-background/35 p-3">
                      <div className="flex items-center justify-between gap-2">
                        <span className="flex items-center gap-2 text-xs text-muted-foreground">
                          <TestTube2 size={13} />
                          {activeFeature.tests.command}
                        </span>
                        <TestHealth failed={activeFeature.tests.failed} />
                      </div>
                      <div className="mt-3 grid grid-cols-2 gap-2">
                        <TestCount label="Passed" value={activeFeature.tests.passed} ok />
                        <TestCount label="Failed" value={activeFeature.tests.failed} ok={activeFeature.tests.failed === 0} />
                      </div>
                    </div>
                    <div className="rounded-md border border-border/70 bg-background/35 p-3">
                      <div className="mb-3 flex items-center gap-2 text-xs font-medium text-muted-foreground">
                        <FileCode2 size={13} />
                        Evidence
                      </div>
                      <div className="space-y-2">
                        {activeFeature.evidence.length === 0 && (
                          <p className="text-sm text-muted-foreground">Noch keine Evidence angebunden.</p>
                        )}
                        {activeFeature.evidence.map((item) => (
                          <div key={item} className="flex items-center gap-2 text-sm">
                            <CheckCircle2 size={14} className="text-emerald-400" />
                            <span>{item}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  </div>
                </section>
              </>
            ) : (
              <div className="flex min-h-[480px] items-center justify-center rounded-md border border-dashed border-border bg-card/35 px-6 text-center">
                <div>
                  <h1 className="text-base font-semibold text-foreground">Keine Features im Cockpit</h1>
                  <p className="mt-2 max-w-md text-sm leading-6 text-muted-foreground">
                    Der Wizard-State enthaelt aktuell keine FEATURES-Eintraege. Sobald Features im Wizard gespeichert sind, erscheinen sie hier in Liste und Graph.
                  </p>
                </div>
              </div>
            )}
          </div>
        </section>
      </main>
    </div>
  );
}

function CockpitFeatureGraph({
  features,
  activeId,
  onSelectFeature,
}: {
  features: CockpitFeature[];
  activeId: string;
  onSelectFeature: (featureId: string) => void;
}) {
  const ctxRef = useRef<FeaturesEditorContext | null>(null);
  const onSelectRef = useRef(onSelectFeature);
  const graph = useMemo(() => toWizardGraph(features, activeId), [features, activeId]);
  const graphRef = useRef(graph);

  useEffect(() => {
    onSelectRef.current = onSelectFeature;
  }, [onSelectFeature]);

  useEffect(() => {
    graphRef.current = graph;
  }, [graph]);

  const editorFactory = useCallback(async (container: HTMLElement) => {
    const ctx = await createFeaturesEditor(container);
    ctx.onNodeDoubleClick((featureId) => onSelectRef.current(featureId));
    ctx.onConnectionCreate(() => false);
    ctx.onNodeMove(() => undefined);
    ctxRef.current = ctx;
    await ctx.applyGraph(graphRef.current.features, graphRef.current.edges);
    await ctx.autoLayout();
    return ctx as unknown as { destroy: () => void };
  }, []);

  const [ref] = useRete(editorFactory);

  const fingerprint = useMemo(() => {
    const nodes = graph.features
      .map((feature) => `${feature.id}:${feature.title}:${feature.description}:${feature.position.x}:${feature.position.y}`)
      .join("|");
    const edges = graph.edges.map((edge) => `${edge.id}:${edge.from}>${edge.to}`).join("|");
    return `${nodes}##${edges}`;
  }, [graph]);

  useEffect(() => {
    if (!ctxRef.current) return;
    ctxRef.current.applyGraph(graph.features, graph.edges);
  }, [fingerprint, graph]);

  return (
    <section className="mt-5 rounded-md border border-border bg-card/45">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-3">
        <div>
          <h2 className="text-sm font-semibold">Feature-Graph</h2>
          <p className="mt-1 text-xs text-muted-foreground">
            Rete.js-Uebersicht der Features. Doppelklick auf einen Node waehlt das Feature aus.
          </p>
        </div>
        <Badge variant="ghost">{features.length} Features</Badge>
      </div>
      <div className="h-[320px] min-w-0 overflow-hidden">
        <div ref={ref} className="h-full w-full" style={{ background: "var(--color-background)" }} />
      </div>
    </section>
  );
}

function toWizardGraph(features: CockpitFeature[], activeId: string): { features: WizardFeature[]; edges: WizardFeatureEdge[] } {
  const wizardFeatures: WizardFeature[] = features.map((feature) => {
    const openItems = feature.reviewItems.filter((item) => !item.resolved).length;
    const activePrefix = feature.id === activeId ? "Aktiv: " : "";
    return {
      id: feature.id,
      title: `${activePrefix}${feature.title}`,
      scopes: feature.id === activeId ? [...feature.scopes, "ACTIVE" as FeatureScope] : feature.scopes,
      description: `${STATUS_LABEL[feature.status]} · ${feature.tests.failed > 0 ? "Tests offen" : "Tests gruen"} · ${openItems} offene Punkte`,
      scopeFields: {},
      acceptanceCriteria: feature.reviewItems.map((item) => ({ id: item.id, text: item.text })),
      position: feature.graphPosition,
    };
  });

  const edges: WizardFeatureEdge[] = features.flatMap((feature) =>
    feature.dependsOn.map((sourceId) => ({
      id: `${sourceId}-${feature.id}`,
      from: sourceId,
      to: feature.id,
    })),
  );

  return { features: wizardFeatures, edges };
}

function FeatureHeader({ feature }: { feature: CockpitFeature }) {
  return (
    <section>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="max-w-3xl">
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <Badge variant={STATUS_BADGE[feature.status]}>{STATUS_LABEL[feature.status]}</Badge>
            <span className="text-xs text-muted-foreground">{feature.owner}</span>
            <span className="text-xs text-muted-foreground">Aktualisiert {feature.updatedAt}</span>
          </div>
          <h1 className="text-xl font-semibold tracking-normal text-foreground">{feature.title}</h1>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-muted-foreground">{feature.summary}</p>
        </div>
      </div>
    </section>
  );
}

function Metric({ value, label, tone }: { value: string; label: string; tone: "primary" | "success" | "warning" | "danger" | "muted" }) {
  const toneClass = {
    primary: "text-primary",
    success: "text-emerald-300",
    warning: "text-amber-300",
    danger: "text-destructive",
    muted: "text-muted-foreground",
  }[tone];

  return (
    <div className="rounded-md border border-border bg-card/60 px-2.5 py-2">
      <div className={cn("text-sm font-semibold tabular-nums", toneClass)}>{value}</div>
      <div className="mt-0.5 truncate text-[11px] text-muted-foreground">{label}</div>
    </div>
  );
}

function TestHealth({ failed }: { failed: number }) {
  if (failed > 0) {
    return (
      <span className="inline-flex items-center gap-1 text-amber-300">
        <AlertTriangle size={12} />
        Tests offen
      </span>
    );
  }

  return (
    <span className="inline-flex items-center gap-1 text-emerald-300">
      <CheckCircle2 size={12} />
      Tests gruen
    </span>
  );
}

function TestCount({ label, value, ok }: { label: string; value: number; ok: boolean }) {
  return (
    <div className="rounded-md bg-secondary/45 px-3 py-2">
      <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
        {ok ? <CheckCircle2 size={12} className="text-emerald-400" /> : <XCircle size={12} className="text-destructive" />}
        {label}
      </div>
      <div className="mt-1 text-lg font-semibold tabular-nums">{value}</div>
    </div>
  );
}

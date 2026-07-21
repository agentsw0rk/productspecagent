"use client";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useShallow } from "zustand/react/shallow";
import { useRete } from "rete-react-plugin";
import { GitCompare, Plus, Sparkles, LayoutGrid } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  useWizardStore,
  selectFeatures,
  selectEdges,
  selectFeatureById,
} from "@/lib/stores/wizard-store";
import { useWizardOptionsStore } from "@/lib/stores/wizard-options-store";
import { proposeFeatures } from "@/lib/api";
import { getAllowedScopesFromCatalog } from "@/lib/category-step-config";
import { createFeaturesEditor, type FeaturesEditorContext } from "./editor";
import { FeatureEditDialog } from "./FeatureEditDialog";
import { FeaturesFallbackList } from "./FeaturesFallbackList";
import { useOkfStore } from "@/lib/stores/okf-store";

interface Props {
  projectId: string;
}

export function FeaturesGraphEditor({ projectId }: Props) {
  // Atomic store subscriptions — each re-renders only when its own slice changes.
  // NEVER destructure `useWizardStore()` without a selector — that subscribes to
  // every store update and forces a full re-render of this component on every
  // auto-save response or other-step mutation.
  const features = useWizardStore(useShallow(selectFeatures));
  const edges = useWizardStore(useShallow(selectEdges));
  const category = useWizardStore(
    (s) => s.data?.steps.IDEA?.fields.category as string | undefined,
  );
  const addFeature = useWizardStore((s) => s.addFeature);
  const removeFeature = useWizardStore((s) => s.removeFeature);
  const addEdge = useWizardStore((s) => s.addEdge);
  const removeEdge = useWizardStore((s) => s.removeEdge);
  const moveFeature = useWizardStore((s) => s.moveFeature);
  const updateFeature = useWizardStore((s) => s.updateFeature);
  const applyProposal = useWizardStore((s) => s.applyProposal);
  const saveStep = useWizardStore((s) => s.saveStep);
  const catalog = useWizardOptionsStore((s) => s.catalog);
  const okfStatus = useOkfStore((s) => s.projectId === projectId ? s.status : null);
  const refreshOkf = useOkfStore((s) => s.refresh);
  const setOkfDialogOpen = useOkfStore((s) => s.setDialogOpen);

  const allowedScopes = useMemo(
    () => getAllowedScopesFromCatalog(catalog, category),
    [catalog, category],
  );

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [viewportWide, setViewportWide] = useState(true);
  const [isSavingLayout, setIsSavingLayout] = useState(false);
  const ctxRef = useRef<FeaturesEditorContext | null>(null);

  // Atomic selector for the active feature — returns the live WizardFeature
  // object so the side panel always renders the latest values (including
  // store-side updates during auto-save).
  const selected =
    useWizardStore(
      useShallow((s) => (selectedId ? selectFeatureById(selectedId)(s) : undefined)),
    ) ?? null;

  useEffect(() => {
    const check = () => setViewportWide(window.innerWidth >= 768);
    check();
    window.addEventListener("resize", check);
    return () => window.removeEventListener("resize", check);
  }, []);

  // Flag read by the post-apply effect below. We cannot call `autoLayout()`
  // straight from the "+ Feature" click: at that moment the Rete node hasn't
  // been rendered yet — `applyGraph` runs in the fingerprint effect on the
  // next React commit. Setting a ref here defers the call until after the
  // node actually exists in the editor.
  const shouldAutoLayoutRef = useRef(false);

  const editorFactory = useCallback(
    async (container: HTMLElement) => {
      const ctx = await createFeaturesEditor(container);
      ctx.onNodeDoubleClick(setSelectedId);
      ctx.onConnectionCreate((from, to) => addEdge(from, to));
      ctx.onCycleRejected(() => {
        alert("Zyklus verhindert: Die Kante wuerde eine Schleife erzeugen.");
      });
      ctx.onNodeMove((id, x, y) => moveFeature(id, { x, y }));
      ctx.onConnectionRemove((edgeId) => removeEdge(edgeId));
      ctxRef.current = ctx;

      // Initial paint — the fingerprint effect below already ran during the
      // render that mounted the editor (ctxRef was still null, so it no-op'd)
      // and won't re-fire until the fingerprint actually changes. Without
      // this call, pre-existing features stay invisible until the user edits
      // the graph. Read the latest store snapshot to stay in sync with any
      // mutations that landed while we were awaiting createFeaturesEditor.
      const state = useWizardStore.getState();
      await ctx.applyGraph(selectFeatures(state), selectEdges(state));
      return ctx as unknown as { destroy: () => void };
    },
    [addEdge, moveFeature, removeEdge],
  );
  const [ref] = useRete(editorFactory);

  // Fingerprint of the graph content — changes only when a node/edge is actually
  // added, removed, renamed, rescoped, or repositioned. We depend on the string,
  // not the array identities, so auto-save responses (which replace `data` but
  // not the feature/edge contents) don't re-trigger applyGraph.
  const fingerprint = useMemo(() => {
    const f = features
      .map(
        (x) =>
          `${x.id}:${x.title}:${x.description}:${[...x.scopes].sort().join(",")}:${x.position.x}:${x.position.y}`,
      )
      .join("|");
    const e = edges.map((x) => `${x.id}:${x.from}>${x.to}`).join("|");
    return `${f}##${e}`;
  }, [features, edges]);

  const semanticFingerprint = useMemo(() => JSON.stringify({
    features: [...features]
      .sort((a, b) => a.id.localeCompare(b.id))
      .map((feature) => ({
        id: feature.id,
        title: feature.title,
        description: feature.description,
        scopes: [...feature.scopes].sort(),
        scopeFields: Object.fromEntries(Object.entries(feature.scopeFields ?? {}).sort(([a], [b]) => a.localeCompare(b))),
        acceptanceCriteria: Array.isArray(feature.acceptanceCriteria)
          ? feature.acceptanceCriteria.map((criterion) => ({ id: criterion.id, text: criterion.text }))
          : [],
      })),
    edges: [...edges]
      .sort((a, b) => a.id.localeCompare(b.id))
      .map(({ id, from, to }) => ({ id, from, to })),
  }), [features, edges]);

  useEffect(() => {
    const timeout = window.setTimeout(() => {
      void refreshOkf(projectId);
    }, 800);
    return () => window.clearTimeout(timeout);
  }, [projectId, refreshOkf, semanticFingerprint]);

  useEffect(() => {
    if (!ctxRef.current) return;
    const runAutoLayout = shouldAutoLayoutRef.current;
    shouldAutoLayoutRef.current = false;
    const ctx = ctxRef.current;
    (async () => {
      await ctx.applyGraph(features, edges);
      if (runAutoLayout) await ctx.autoLayout();
    })();
    // Depend on fingerprint only — features/edges identities are already
    // captured in it. The editor's coalescing queue (see editor.ts) serializes
    // concurrent calls safely.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fingerprint]);

  if (!viewportWide) return <FeaturesFallbackList />;

  return (
    <div className="flex h-full min-h-[400px] rounded-lg border bg-background overflow-hidden">
      <div className="flex-1 min-w-0 flex flex-col">
        <div
          ref={ref}
          className="flex-1"
          style={{ background: "var(--color-background)" }}
        />
        <div className="border-t px-3 py-2 flex flex-col gap-1.5">
          <div className="flex items-center gap-2">
            <Button
              size="sm"
              onClick={() => {
                const id = addFeature({
                  title: "Neues Feature",
                  description: "",
                  scopes: allowedScopes.slice(0, 1),
                  scopeFields: {},
                  acceptanceCriteria: [],
                  position: { x: 0, y: 0 },
                });
                setSelectedId(id);
                shouldAutoLayoutRef.current = true;
              }}
            >
              <Plus size={14} /> Feature
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={async () => {
                if (features.length > 0 && !confirm("Bestehenden Graph ueberschreiben?"))
                  return;
                try {
                  const g = await proposeFeatures(projectId);
                  applyProposal(g);
                } catch {
                  alert("Vorschlag fehlgeschlagen");
                }
              }}
            >
              <Sparkles size={14} /> Vorschlagen
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={isSavingLayout}
              onClick={async () => {
                const ctx = ctxRef.current;
                if (!ctx) return;
                setIsSavingLayout(true);
                try {
                  await ctx.autoLayout();
                  const saved = await saveStep(projectId, "FEATURES");
                  if (!saved) alert("Auto-Layout konnte nicht gespeichert werden.");
                } finally {
                  setIsSavingLayout(false);
                }
              }}
            >
              <LayoutGrid size={14} /> {isSavingLayout ? "Speichere..." : "Auto-Layout"}
            </Button>
            {okfStatus?.state.syncStatus === "PROPOSAL_PENDING" && (
              <Button
                size="sm"
                variant="outline"
                onClick={() => setOkfDialogOpen(true)}
              >
                <GitCompare size={14} /> Aenderungen pruefen
              </Button>
            )}
          </div>
          <p className="text-xs text-muted-foreground">
            Berücksichtigt Markdown- und Text-Dateien aus dem Documents-Tab.
          </p>
        </div>
      </div>

      <FeatureEditDialog
        feature={selected}
        allowedScopes={allowedScopes}
        open={selectedId !== null}
        projectId={projectId}
        onClose={() => setSelectedId(null)}
        onSave={(patch) => {
          if (selected) updateFeature(selected.id, patch);
        }}
        onDelete={() => {
          if (selected) {
            removeFeature(selected.id);
            setSelectedId(null);
          }
        }}
      />
    </div>
  );
}

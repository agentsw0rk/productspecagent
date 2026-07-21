"use client";

import { useEffect, useState, use } from "react";
import Link from "next/link";
import { Activity, ArrowLeft, ChevronLeft, ChevronRight, Gauge, Loader2, Scale, MessageSquare, HelpCircle, Layers, Download, ShieldCheck, Bot, FolderTree, FileText, Save } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ExportDialog } from "@/components/export/ExportDialog";
import { HandoffDialog } from "@/components/handoff/HandoffDialog";
import { ChatPanel } from "@/components/chat/ChatPanel";
import { DecisionLog } from "@/components/decisions/DecisionLog";
import { ClarificationList } from "@/components/clarifications/ClarificationList";
import { useProjectStore } from "@/lib/stores/project-store";
import { useDecisionStore } from "@/lib/stores/decision-store";
import { useClarificationStore } from "@/lib/stores/clarification-store";
import { useTaskStore } from "@/lib/stores/task-store";
import { useDesignWorkbenchStore } from "@/lib/stores/design-workbench-store";
import { TaskTree } from "@/components/tasks/TaskTree";
import { CheckResultsPanel } from "@/components/checks/CheckResultsPanel";
import { DocumentsPanel } from "@/components/documents/DocumentsPanel";
import { LivingSyncPanel } from "@/components/living-sync/LivingSyncPanel";
import { GraphMeshSettings } from "@/components/workspace/GraphMeshToggle";
import { ExplorerPanel } from "@/components/explorer/ExplorerPanel";
import { StepIndicator } from "@/components/wizard/StepIndicator";
import { WizardForm } from "@/components/wizard/WizardForm";
import { useWizardStore } from "@/lib/stores/wizard-store";
import { isCockpitReady } from "@/lib/project-cockpit";
import { cn } from "@/lib/utils";
import { useResizable } from "@/lib/hooks/use-resizable";
import { ResizeHandle } from "@/components/layout/ResizeHandle";
import { OkfSyncDialog } from "@/components/okf/OkfSyncDialog";
import { useOkfStore } from "@/lib/stores/okf-store";

interface PageProps {
  params: Promise<{ id: string }>;
}

export default function ProjectWorkspacePage({ params }: PageProps) {
  const { id } = use(params);
  const {
    project, projectLoading, projectError,
    flowState,
    loadProject, reset, setProject,
  } = useProjectStore();

  const [showExplorer, setShowExplorer] = useState(false);
  const [showExport, setShowExport] = useState(false);
  const [showHandoff, setShowHandoff] = useState(false);
  const [showProjectOptions, setShowProjectOptions] = useState(false);
  const [okfRevision, setOkfRevision] = useState(0);
  const [panelCollapsed, setPanelCollapsed] = useState(false);
  const [rightTab, setRightTab] = useState<"chat" | "decisions" | "clarifications" | "tasks" | "checks" | "documents" | "living-sync">("chat");
  const { decisions, loadDecisions: loadDecs, reset: resetDecs } = useDecisionStore();
  const pendingCount = decisions.filter((d) => d.status === "PENDING").length;
  const { clarifications: clars, loadClarifications: loadClars, reset: resetClars } = useClarificationStore();
  const openClarCount = clars.filter((c) => c.status === "OPEN").length;
  const { tasks, loadTasks: loadTsks, loadCoverage, reset: resetTasks } = useTaskStore();

  useEffect(() => {
    reset();
    resetDecs();
    resetClars();
    resetTasks();
    useWizardStore.getState().reset();
    useOkfStore.getState().reset();
    useDesignWorkbenchStore.getState().reset();
    loadProject(id);
    loadDecs(id);
    loadClars(id);
    loadTsks(id);
    loadCoverage(id);
    useWizardStore.getState().loadWizard(id);
    useDesignWorkbenchStore.getState().load(id);
  }, [id]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    function onToggleOptions() {
      setShowProjectOptions((open) => !open);
    }
    function onEscape(e: KeyboardEvent) {
      if (e.key === "Escape") setShowProjectOptions(false);
    }
    window.addEventListener("spec-agent:toggle-project-options", onToggleOptions);
    document.addEventListener("keydown", onEscape);
    return () => {
      window.removeEventListener("spec-agent:toggle-project-options", onToggleOptions);
      document.removeEventListener("keydown", onEscape);
    };
  }, []);

  const { width: sidebarWidth, isDragging, handleProps } = useResizable({
    initialWidth: 600,
    minWidth: 280,
    maxWidth: 900,
  });
  const cockpitReady = isCockpitReady(flowState);

  if (projectLoading) {
    return (
      <div className="flex h-full items-center justify-center bg-background">
        <Loader2 size={24} className="animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (projectError) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 bg-background">
        <p className="text-sm text-destructive">{projectError}</p>
        <Link href="/projects" className="text-sm text-primary hover:underline">Zurueck zu Projekten</Link>
      </div>
    );
  }

  return (
    <div className="relative flex h-full flex-col bg-background overflow-hidden">
      <header className="flex h-12 shrink-0 items-center justify-between gap-3 border-b border-border bg-background px-5">
        <div className="flex min-w-0 items-center gap-2 text-xs text-muted-foreground">
          <Link
            href="/projects"
            className="inline-flex items-center gap-1 rounded-md px-1.5 py-1 transition-colors hover:bg-secondary hover:text-foreground"
          >
            <ArrowLeft size={13} />
            Projekte
          </Link>
          <span className="opacity-50">/</span>
          <span className="truncate text-[13px] font-semibold text-foreground">{project?.name ?? "..."}</span>
        </div>

        <div className="flex shrink-0 items-center gap-2">
          {cockpitReady && (
            <Link href={`/projects/${id}/cockpit`}>
              <Button variant="outline" size="sm" className="gap-1.5">
                <Gauge size={14} /> Cockpit
              </Button>
            </Link>
          )}
          <Button variant="outline" size="sm" onClick={() => setShowHandoff(true)} className="gap-1.5">
            <Save size={14} /> Handoff
          </Button>
          <Button variant="outline" size="sm" onClick={() => setShowExport(true)} className="gap-1.5">
            <Download size={14} /> Exportieren
          </Button>
          <button
            type="button"
            onClick={() => setShowExplorer(!showExplorer)}
            title={showExplorer ? "Explorer ausblenden" : "Explorer einblenden"}
            aria-label={showExplorer ? "Explorer ausblenden" : "Explorer einblenden"}
            className={cn(
              "inline-flex h-[30px] items-center gap-1.5 rounded-md border px-2.5 text-xs font-medium transition-colors",
              showExplorer
                ? "border-primary/20 bg-primary/10 text-primary hover:bg-secondary"
                : "border-border text-muted-foreground hover:bg-secondary hover:text-foreground"
            )}
          >
            <FolderTree size={14} />
            {showExplorer ? <ChevronLeft size={13} /> : <ChevronRight size={13} />}
          </button>
          <button
            type="button"
            onClick={() => setPanelCollapsed((value) => !value)}
            title={panelCollapsed ? "Chat & Clarifications einblenden" : "Chat & Clarifications ausblenden"}
            aria-label={panelCollapsed ? "Chat & Clarifications einblenden" : "Chat & Clarifications ausblenden"}
            className={cn(
              "inline-flex h-[30px] items-center gap-1.5 rounded-md border px-2.5 text-xs font-medium transition-colors",
              panelCollapsed
                ? "border-border text-muted-foreground hover:bg-secondary hover:text-foreground"
                : "border-primary/20 bg-primary/10 text-primary hover:bg-secondary"
            )}
          >
            <Bot size={14} />
            {panelCollapsed ? <ChevronLeft size={13} /> : <ChevronRight size={13} />}
          </button>
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        {/* Explorer Panel */}
        {showExplorer && (
          <div className="w-60 shrink-0 border-r border-border bg-card overflow-hidden animate-slide-in-left">
            <ExplorerPanel projectId={id} flowState={flowState} reloadToken={okfRevision} />
          </div>
        )}

        {/* Wizard area */}
        <div className="flex flex-1 flex-col overflow-hidden border-r">
          <StepIndicator />
          <div className="flex-1 overflow-hidden">
            <WizardForm
              projectId={id}
              onBlockerClick={(tab) => setRightTab(tab)}
              onExportClick={() => setShowExport(true)}
            />
          </div>
        </div>
        {!panelCollapsed && (
        <div className="shrink-0 overflow-hidden flex flex-row animate-slide-in-right" style={{ width: sidebarWidth }}>
          <ResizeHandle isDragging={isDragging} onMouseDown={handleProps.onMouseDown} />
          <div className="flex-1 overflow-hidden flex flex-col border-l border-border">
            {/* Tab buttons */}
            <div className="flex h-12 shrink-0 items-stretch overflow-x-auto border-b border-border bg-background px-4">
            <button
              onClick={() => setRightTab("chat")}
              className={cn(
                "flex shrink-0 items-center justify-center gap-1.5 border-b-2 border-transparent px-3 text-xs font-medium transition-colors",
                rightTab === "chat" ? "border-primary text-primary" : "text-muted-foreground hover:text-foreground"
              )}
            >
              <MessageSquare size={13} /> Chat
            </button>
            <button
              onClick={() => setRightTab("decisions")}
              className={cn(
                "flex shrink-0 items-center justify-center gap-1.5 border-b-2 border-transparent px-3 text-xs font-medium transition-colors",
                rightTab === "decisions" ? "border-primary text-primary" : "text-muted-foreground hover:text-foreground"
              )}
            >
              <Scale size={13} /> Decisions
              {pendingCount > 0 && (
                <span className="rounded-full bg-primary px-1.5 py-0.5 text-[10px] text-primary-foreground">{pendingCount}</span>
              )}
            </button>
            <button
              onClick={() => setRightTab("clarifications")}
              className={cn(
                "flex shrink-0 items-center justify-center gap-1.5 border-b-2 border-transparent px-3 text-xs font-medium transition-colors",
                rightTab === "clarifications" ? "border-primary text-primary" : "text-muted-foreground hover:text-foreground"
              )}
            >
              <HelpCircle size={13} /> Clarifications
              {openClarCount > 0 && (
                <span className="rounded-full bg-amber-500/20 px-1.5 py-0.5 text-[10px] text-amber-400">{openClarCount}</span>
              )}
            </button>
            <button
              onClick={() => setRightTab("tasks")}
              className={cn(
                "flex shrink-0 items-center justify-center gap-1.5 border-b-2 border-transparent px-3 text-xs font-medium transition-colors",
                rightTab === "tasks" ? "border-primary text-primary" : "text-muted-foreground hover:text-foreground"
              )}
            >
              <Layers size={13} /> Tasks
              {tasks.length > 0 && (
                <span className="rounded-full bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">{tasks.length}</span>
              )}
            </button>
            <button
              onClick={() => setRightTab("checks")}
              className={cn(
                "flex shrink-0 items-center justify-center gap-1.5 border-b-2 border-transparent px-3 text-xs font-medium transition-colors",
                rightTab === "checks" ? "border-primary text-primary" : "text-muted-foreground hover:text-foreground"
              )}
            >
              <ShieldCheck size={13} /> Checks
            </button>
            <button
              onClick={() => setRightTab("documents")}
              className={cn(
                "flex shrink-0 items-center justify-center gap-1.5 border-b-2 border-transparent px-3 text-xs font-medium transition-colors",
                rightTab === "documents" ? "border-primary text-primary" : "text-muted-foreground hover:text-foreground"
              )}
            >
              <FileText size={13} /> Documents
            </button>
            <button
              onClick={() => setRightTab("living-sync")}
              className={cn(
                "flex shrink-0 items-center justify-center gap-1.5 border-b-2 border-transparent px-3 text-xs font-medium transition-colors",
                rightTab === "living-sync" ? "border-primary text-primary" : "text-muted-foreground hover:text-foreground"
              )}
            >
              <Activity size={13} /> Sync
            </button>
            </div>
            {/* Tab content */}
            <div className="flex-1 overflow-hidden">
              {rightTab === "chat" ? (
                <ChatPanel projectId={id} disableComposer />
              ) : rightTab === "decisions" ? (
                <DecisionLog projectId={id} />
              ) : rightTab === "clarifications" ? (
                <ClarificationList projectId={id} />
              ) : rightTab === "tasks" ? (
                <TaskTree projectId={id} />
              ) : rightTab === "checks" ? (
                <CheckResultsPanel projectId={id} />
              ) : rightTab === "documents" ? (
                <DocumentsPanel projectId={id} />
              ) : (
                <LivingSyncPanel projectId={id} />
              )}
            </div>
          </div>
        </div>
        )}
      </div>
      {showProjectOptions && project && (
        <div
          role="dialog"
          aria-label="Projekt-Optionen"
          className="absolute bottom-3 left-3 z-50 w-80 rounded-lg border border-border bg-card p-3 shadow-md ring-1 ring-foreground/10 animate-scale-in"
        >
          <div className="mb-3 flex items-start justify-between gap-3">
            <div>
              <h2 className="text-sm font-semibold">Projekt-Optionen</h2>
              <p className="mt-0.5 text-[11px] text-muted-foreground">Einstellungen fuer den aktuellen Workspace.</p>
            </div>
            <button
              type="button"
              onClick={() => setShowProjectOptions(false)}
              className="rounded-md px-2 py-1 text-[11px] text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
            >
              Schliessen
            </button>
          </div>
          <div className="rounded-md border border-border bg-background/60 p-3">
            <GraphMeshSettings project={project} onProjectUpdate={setProject} />
          </div>
        </div>
      )}
      <ExportDialog
        projectId={id}
        projectName={project?.name ?? "Project"}
        open={showExport}
        onClose={() => setShowExport(false)}
      />
      <HandoffDialog
        projectId={id}
        projectName={project?.name ?? "Project"}
        open={showHandoff}
        onClose={() => setShowHandoff(false)}
      />
      <OkfSyncDialog
        projectId={id}
        onConfirmed={() => setOkfRevision((revision) => revision + 1)}
      />
    </div>
  );
}

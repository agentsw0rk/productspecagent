"use client";

import { useState, useEffect } from "react";
import { Bug, FolderTree, GitBranch, Loader2, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { FileTree } from "./FileTree";
import { SpecFileViewer } from "./SpecFileViewer";
import { listProjectFiles, type FileEntry } from "@/lib/api";
import type { FlowState } from "@/lib/api";
import { cn } from "@/lib/utils";
import { useOkfStore } from "@/lib/stores/okf-store";

const HIDDEN_FOLDERS = new Set(["clarifications", "decisions", "tasks"]);

function filterEntries(entries: FileEntry[], debug: boolean): FileEntry[] {
  if (debug) return entries;
  return entries
    .filter((e) => {
      if (e.isDirectory && HIDDEN_FOLDERS.has(e.name.toLowerCase())) return false;
      if (!e.isDirectory && e.name.endsWith(".json")) return false;
      return true;
    })
    .map((e) =>
      e.isDirectory && e.children
        ? { ...e, children: filterEntries(e.children, debug) }
        : e
    );
}

type ExplorerStatus = "idle" | "generating" | "valid" | "stale";

const STATUS_CONFIG: Record<ExplorerStatus, { variant: "ghost" | "default" | "success" | "warning"; label: string }> = {
  idle: { variant: "ghost", label: "Idle" },
  generating: { variant: "default", label: "Generating..." },
  valid: { variant: "success", label: "Valid" },
  stale: { variant: "warning", label: "Stale" },
};

interface ExplorerPanelProps {
  projectId: string;
  flowState: FlowState | null;
  reloadToken?: number;
}

const OKF_STATUS_CONFIG = {
  CLEAN: { variant: "success" as const, label: "OKF Clean" },
  INCOMING: { variant: "default" as const, label: "Incoming" },
  PROPOSAL_PENDING: { variant: "warning" as const, label: "Review" },
  CONFLICT: { variant: "destructive" as const, label: "Konflikt" },
  INVALID_REMOTE: { variant: "destructive" as const, label: "Remote fehlerhaft" },
  PUSH_FAILED: { variant: "destructive" as const, label: "Push fehlgeschlagen" },
};

export function ExplorerPanel({ projectId, flowState, reloadToken = 0 }: ExplorerPanelProps) {
  const [files, setFiles] = useState<FileEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [viewerPath, setViewerPath] = useState<string | null>(null);
  const [debug, setDebug] = useState(false);
  const okfStatus = useOkfStore((state) => state.projectId === projectId ? state.status : null);
  const refreshOkf = useOkfStore((state) => state.refresh);
  const setOkfDialogOpen = useOkfStore((state) => state.setDialogOpen);

  const completedSteps = flowState?.steps.filter((s) => s.status === "COMPLETED").length ?? 0;
  const status: ExplorerStatus = loading ? "generating" : files.length > 0 ? "valid" : "idle";
  const statusConfig = STATUS_CONFIG[status];
  const connectedOkf = okfStatus?.state.connection
    ? OKF_STATUS_CONFIG[okfStatus.state.syncStatus]
    : null;

  async function loadFiles() {
    setLoading(true);
    try {
      const entries = await listProjectFiles(projectId);
      setFiles(entries);
    } catch {
      setFiles([]);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void Promise.all([loadFiles(), refreshOkf(projectId)]);
  }, [projectId, completedSteps, reloadToken]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <>
      <div className="h-full flex flex-col overflow-hidden">
        {/* Header */}
        <div className="px-3 py-2.5 border-b flex items-center justify-between shrink-0">
          <div className="flex items-center gap-2">
            <FolderTree size={14} className="text-primary" />
            <span className="text-xs font-semibold">Explorer</span>
          </div>
          <div className="flex items-center gap-1.5">
            <Badge variant={(connectedOkf ?? statusConfig).variant}>
              {(connectedOkf ?? statusConfig).label}
            </Badge>
            <Button
              size="icon-xs"
              variant="ghost"
              onClick={() => setOkfDialogOpen(true)}
              title="OKF-Synchronisation"
              aria-label="OKF-Synchronisation"
            >
              <GitBranch size={11} />
            </Button>
            <Button
              size="icon-xs"
              variant="ghost"
              onClick={() => setDebug(!debug)}
              title={debug ? "Debug: An" : "Debug: Aus"}
              className={cn(debug && "text-amber-500")}
            >
              <Bug size={11} />
            </Button>
            <Button
              size="icon-xs"
              variant="ghost"
              onClick={loadFiles}
              disabled={loading}
              title="Dateien aktualisieren"
              aria-label="Dateien aktualisieren"
            >
              {loading ? <Loader2 size={11} className="animate-spin" /> : <RefreshCw size={11} />}
            </Button>
          </div>
        </div>

        {/* File Tree */}
        <div className="flex-1 overflow-y-auto py-1">
          {loading && files.length === 0 ? (
            <div className="flex items-center justify-center py-8 text-muted-foreground">
              <Loader2 size={16} className="animate-spin" />
            </div>
          ) : files.length === 0 ? (
            <div className="px-3 py-8 text-center text-xs text-muted-foreground">
              No files yet. Start the spec flow to generate files.
            </div>
          ) : (
            <FileTree entries={filterEntries(files, debug)} onFileClick={(path) => setViewerPath(path)} />
          )}
        </div>
      </div>

      {/* File Viewer Modal */}
      <SpecFileViewer
        projectId={projectId}
        initialPath={viewerPath ?? ""}
        open={viewerPath !== null}
        onClose={() => setViewerPath(null)}
      />
    </>
  );
}

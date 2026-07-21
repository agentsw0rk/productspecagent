"use client";

import { useEffect, useState } from "react";
import { AlertTriangle, Check, GitBranch, GitCompare, Loader2, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { OkfChangeset, OkfConflict, OkfMigrationPreview } from "@/lib/api";
import { useOkfStore } from "@/lib/stores/okf-store";
import { useWizardStore } from "@/lib/stores/wizard-store";
import { OkfDiffView } from "./OkfDiffView";

interface OkfSyncDialogProps {
  projectId: string;
  onConfirmed?: () => void | Promise<void>;
}

export function OkfSyncDialog({ projectId, onConfirmed }: OkfSyncDialogProps) {
  const storeProjectId = useOkfStore((state) => state.projectId);
  const projectMatches = storeProjectId === projectId;
  const status = useOkfStore((state) => state.projectId === projectId ? state.status : null);
  const migration = useOkfStore((state) => state.projectId === projectId ? state.migration : null);
  const loading = useOkfStore((state) => state.projectId === projectId ? state.loading : true);
  const error = useOkfStore((state) => state.projectId === projectId ? state.error : null);
  const open = useOkfStore((state) => state.dialogOpen);
  const setOpen = useOkfStore((state) => state.setDialogOpen);
  const connect = useOkfStore((state) => state.connect);
  const dryRun = useOkfStore((state) => state.dryRun);
  const prepare = useOkfStore((state) => state.prepare);
  const approve = useOkfStore((state) => state.approve);
  const sync = useOkfStore((state) => state.sync);
  const resolve = useOkfStore((state) => state.resolve);
  const refresh = useOkfStore((state) => state.refresh);
  const loadWizard = useWizardStore((state) => state.loadWizard);
  const [remoteUrl, setRemoteUrl] = useState("");
  const [baseBranch, setBaseBranch] = useState("main");

  useEffect(() => {
    if (open && (useOkfStore.getState().projectId !== projectId || !useOkfStore.getState().status)) {
      void refresh(projectId);
    }
  }, [open, projectId, refresh]);

  async function confirmAction(action: () => Promise<void>) {
    await action();
    const actionError = useOkfStore.getState().error;
    await onConfirmed?.();
    if (!actionError) {
      await loadWizard(projectId);
    }
  }

  const state = status?.state;
  const pending = status?.pending;
  const issues = pending?.issues ?? migration?.issues ?? [];
  const hasBlockingIssues = issues.some((issue) => issue.severity === "ERROR");

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-h-[calc(100vh-2rem)] overflow-hidden sm:max-w-5xl">
        <DialogHeader className="border-b border-border pb-3 pr-8">
          <DialogTitle className="flex items-center gap-2">
            <GitBranch size={16} className="text-primary" />
            OKF-Synchronisation
          </DialogTitle>
          <DialogDescription>
            Feature-Dokumente in <code className="font-mono text-xs">docs/okf/</code> prüfen und mit Git synchronisieren.
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 overflow-y-auto pr-1">
          {(!projectMatches || loading) && !status ? (
            <div className="flex h-40 items-center justify-center text-muted-foreground">
              <Loader2 size={18} className="animate-spin" />
            </div>
          ) : !state?.connection ? (
            <ConnectionForm
              remoteUrl={remoteUrl}
              baseBranch={baseBranch}
              loading={loading}
              onRemoteUrlChange={setRemoteUrl}
              onBaseBranchChange={setBaseBranch}
              onConnect={() => connect(projectId, remoteUrl.trim(), baseBranch.trim() || "main")}
            />
          ) : state.syncStatus === "CONFLICT" ? (
            <ConflictWorkflow
              conflicts={status?.conflicts ?? []}
              loading={loading}
              onResolve={(resolution) => confirmAction(() => resolve(projectId, resolution))}
            />
          ) : state.syncStatus === "PUSH_FAILED" ? (
            <PendingWorkflow
              title="Push fehlgeschlagen"
              description={state.lastError ?? "Die Änderungen bleiben erhalten und können erneut übertragen werden."}
              pending={pending}
              loading={loading}
              hasBlockingIssues={hasBlockingIssues}
              retry
              onApprove={() => confirmAction(() => approve(projectId))}
            />
          ) : pending ? (
            <PendingWorkflow
              title={pending.kind === "MIGRATION" ? "Migration prüfen" : "Änderungen prüfen"}
              description="Diese Änderungen werden erst nach deiner Freigabe in den kanonischen OKF-Branch geschrieben."
              pending={pending}
              loading={loading}
              hasBlockingIssues={hasBlockingIssues}
              onApprove={() => confirmAction(() => approve(projectId))}
            />
          ) : state.mode === "LEGACY" ? (
            <MigrationWorkflow
              migration={migration}
              loading={loading}
              hasBlockingIssues={hasBlockingIssues}
              onDryRun={() => dryRun(projectId)}
              onPrepare={() => prepare(projectId)}
            />
          ) : (
            <ActiveWorkflow
              commit={state.baseCommit}
              error={state.lastError}
              invalidRemote={state.syncStatus === "INVALID_REMOTE"}
              loading={loading}
              onSync={() => confirmAction(() => sync(projectId))}
            />
          )}

          {error && (
            <div className="mt-3 flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-xs text-destructive">
              <AlertTriangle size={14} className="mt-0.5 shrink-0" />
              <span>{error}</span>
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

function ConnectionForm({
  remoteUrl,
  baseBranch,
  loading,
  onRemoteUrlChange,
  onBaseBranchChange,
  onConnect,
}: {
  remoteUrl: string;
  baseBranch: string;
  loading: boolean;
  onRemoteUrlChange(value: string): void;
  onBaseBranchChange(value: string): void;
  onConnect(): void;
}) {
  return (
    <form
      className="space-y-4 py-2"
      onSubmit={(event) => {
        event.preventDefault();
        onConnect();
      }}
    >
      <div className="space-y-1.5">
        <Label htmlFor="okf-remote-url">Git-Remote</Label>
        <Input
          id="okf-remote-url"
          type="url"
          required
          placeholder="https://git.example.com/team/project.git"
          value={remoteUrl}
          onChange={(event) => onRemoteUrlChange(event.target.value)}
        />
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="okf-base-branch">Basis-Branch</Label>
        <Input
          id="okf-base-branch"
          required
          value={baseBranch}
          onChange={(event) => onBaseBranchChange(event.target.value)}
        />
      </div>
      <div className="flex justify-end border-t border-border pt-3">
        <Button type="submit" disabled={loading || !remoteUrl.trim()}>
          {loading ? <Loader2 size={14} className="animate-spin" /> : <GitBranch size={14} />}
          Verbinden
        </Button>
      </div>
    </form>
  );
}

function MigrationWorkflow({ migration, loading, hasBlockingIssues, onDryRun, onPrepare }: {
  migration: OkfMigrationPreview | null;
  loading: boolean;
  hasBlockingIssues: boolean;
  onDryRun(): void;
  onPrepare(): void;
}) {
  return (
    <div className="space-y-4">
      <div>
        <h3 className="text-sm font-medium">Bestehende Features migrieren</h3>
        <p className="mt-1 text-xs text-muted-foreground">
          Die Vorschau erzeugt noch keinen Commit und ändert den aktiven Datenbestand nicht.
        </p>
      </div>
      {migration && (
        <>
          <IssueList issues={migration.issues} />
          <OkfDiffView operations={migration.operations} />
        </>
      )}
      <DialogFooter className="mx-0 mb-0 rounded-none px-0 pb-0">
        <Button variant="outline" onClick={onDryRun} disabled={loading}>
          {loading ? <Loader2 size={14} className="animate-spin" /> : <GitCompare size={14} />}
          {migration ? "Vorschau aktualisieren" : "Migration prüfen"}
        </Button>
        {migration && (
          <Button onClick={onPrepare} disabled={loading || hasBlockingIssues}>
            <Check size={14} /> Migration vorbereiten
          </Button>
        )}
      </DialogFooter>
    </div>
  );
}

function PendingWorkflow({ title, description, pending, loading, hasBlockingIssues, retry = false, onApprove }: {
  title: string;
  description: string;
  pending: OkfChangeset | null | undefined;
  loading: boolean;
  hasBlockingIssues: boolean;
  retry?: boolean;
  onApprove(): void;
}) {
  if (!pending) {
    return <p className="py-8 text-center text-xs text-muted-foreground">Kein ausstehender Änderungssatz verfügbar.</p>;
  }
  return (
    <div className="space-y-4">
      <div>
        <h3 className="text-sm font-medium">{title}</h3>
        <p className="mt-1 text-xs text-muted-foreground">{description}</p>
      </div>
      <IssueList issues={pending.issues} />
      <OkfDiffView operations={pending.operations} />
      <DialogFooter className="mx-0 mb-0 rounded-none px-0 pb-0">
        <Button onClick={onApprove} disabled={loading || hasBlockingIssues}>
          {loading ? <Loader2 size={14} className="animate-spin" /> : retry ? <RefreshCw size={14} /> : <Check size={14} />}
          {retry ? "Freigabe erneut versuchen" : "Änderungen freigeben"}
        </Button>
      </DialogFooter>
    </div>
  );
}

function ConflictWorkflow({ conflicts, loading, onResolve }: {
  conflicts: OkfConflict[];
  loading: boolean;
  onResolve(resolution: "REMOTE" | "PROPOSED"): void;
}) {
  const conflict = conflicts[0];
  return (
    <div className="space-y-4">
      <div>
        <h3 className="flex items-center gap-2 text-sm font-medium">
          <AlertTriangle size={15} className="text-amber-500" /> Konflikt lösen
        </h3>
        <p className="mt-1 text-xs text-muted-foreground">
          Löse Konflikte nacheinander. Wähle für die aktuelle Datei die Remote- oder die vorgeschlagene Fassung.
        </p>
      </div>
      {conflict ? (
        <div className="space-y-2">
          <p className="text-xs text-muted-foreground">Konflikt 1 von {conflicts.length}</p>
          <section className="overflow-hidden rounded-md border border-border">
            <header className="border-b border-border bg-muted/40 px-3 py-2 font-mono text-xs">{conflict.path}</header>
            <div className="grid grid-cols-1 divide-y divide-border lg:grid-cols-3 lg:divide-x lg:divide-y-0">
              <ConflictContent label="Basis" content={conflict.baseContent} />
              <ConflictContent label="Remote" content={conflict.remoteContent} />
              <ConflictContent label="Vorschlag" content={conflict.proposedContent} />
            </div>
          </section>
        </div>
      ) : (
        <p className="py-8 text-center text-xs text-muted-foreground">Keine Konfliktdetails verfügbar.</p>
      )}
      <DialogFooter className="mx-0 mb-0 rounded-none px-0 pb-0">
        <Button variant="outline" onClick={() => onResolve("REMOTE")} disabled={loading || !conflict}>
          Remote übernehmen
        </Button>
        <Button onClick={() => onResolve("PROPOSED")} disabled={loading || !conflict}>
          <Check size={14} /> Vorschlag übernehmen
        </Button>
      </DialogFooter>
    </div>
  );
}

function ConflictContent({ label, content }: { label: string; content?: string | null }) {
  return (
    <div className="min-w-0">
      <div className="border-b border-border px-3 py-1.5 text-[11px] font-medium text-muted-foreground">{label}</div>
      <pre className="max-h-64 min-h-24 overflow-auto whitespace-pre-wrap break-words p-3 font-mono text-xs leading-5">{content ?? ""}</pre>
    </div>
  );
}

function ActiveWorkflow({ commit, error, invalidRemote, loading, onSync }: {
  commit: string | null;
  error: string | null;
  invalidRemote: boolean;
  loading: boolean;
  onSync(): void;
}) {
  return (
    <div className="space-y-4">
      <div className="flex items-start gap-3 rounded-md border border-border px-3 py-3">
        {invalidRemote
          ? <AlertTriangle size={16} className="mt-0.5 shrink-0 text-destructive" />
          : <Check size={16} className="mt-0.5 shrink-0 text-emerald-500" />}
        <div className="min-w-0">
          <p className="text-sm font-medium">{invalidRemote ? "Remote-Bundle fehlerhaft" : "OKF ist aktiv"}</p>
          <p className="mt-1 truncate font-mono text-xs text-muted-foreground">Commit: {commit ?? "Noch kein Commit"}</p>
          {error && <p className="mt-2 text-xs text-destructive">{error}</p>}
        </div>
      </div>
      <DialogFooter className="mx-0 mb-0 rounded-none px-0 pb-0">
        <Button onClick={onSync} disabled={loading}>
          {loading ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
          Synchronisieren
        </Button>
      </DialogFooter>
    </div>
  );
}

function IssueList({ issues }: { issues: Array<{ severity: "ERROR" | "WARNING"; path: string; message: string }> }) {
  if (issues.length === 0) return null;
  return (
    <div className="space-y-1.5">
      {issues.map((issue, index) => (
        <div
          key={`${issue.path}:${issue.message}:${index}`}
          className={`flex items-start gap-2 rounded-md border px-3 py-2 text-xs ${
            issue.severity === "ERROR"
              ? "border-destructive/40 bg-destructive/10 text-destructive"
              : "border-amber-500/30 bg-amber-500/10 text-amber-600 dark:text-amber-400"
          }`}
        >
          <AlertTriangle size={13} className="mt-0.5 shrink-0" />
          <span><code className="font-mono">{issue.path}</code>: {issue.message}</span>
        </div>
      ))}
    </div>
  );
}

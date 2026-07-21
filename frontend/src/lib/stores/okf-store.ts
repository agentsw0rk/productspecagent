import { create } from "zustand";
import {
  ApiError,
  approveOkfChangeset,
  connectOkf,
  dryRunOkfMigration,
  getOkfStatus,
  prepareOkfMigration,
  resolveOkfConflict,
  syncOkf,
  type OkfConflictResolution,
  type OkfMigrationPreview,
  type OkfStatusResponse,
} from "@/lib/api";

interface OkfStoreState {
  projectId: string | null;
  status: OkfStatusResponse | null;
  migration: OkfMigrationPreview | null;
  loading: boolean;
  error: string | null;
  dialogOpen: boolean;
  setDialogOpen(open: boolean): void;
  refresh(projectId: string): Promise<void>;
  connect(projectId: string, remoteUrl: string, baseBranch: string): Promise<void>;
  dryRun(projectId: string): Promise<void>;
  prepare(projectId: string): Promise<void>;
  approve(projectId: string): Promise<void>;
  sync(projectId: string): Promise<void>;
  resolve(projectId: string, resolution: OkfConflictResolution): Promise<void>;
  reset(): void;
}

let requestGeneration = 0;
let activeMutationProjectId: string | null = null;
let queuedRefreshProjectId: string | null = null;

function backendErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.body && typeof error.body === "object") {
    const body = error.body as Record<string, unknown>;
    for (const key of ["message", "detail", "error"] as const) {
      const value = body[key];
      if (typeof value === "string" && value.trim()) return value;
    }
  }
  return error instanceof Error ? error.message : String(error);
}

export const useOkfStore = create<OkfStoreState>((set, get) => {
  const begin = (projectId: string): number => {
    const generation = ++requestGeneration;
    const current = get();
    const projectChanged = current.projectId !== projectId;
    set({
      projectId,
      status: projectChanged ? null : current.status,
      migration: projectChanged ? null : current.migration,
      loading: true,
      error: null,
      dialogOpen: current.dialogOpen,
    });
    return generation;
  };

  const isCurrent = (generation: number, projectId: string): boolean =>
    generation === requestGeneration && get().projectId === projectId;

  const fail = (generation: number, projectId: string, error: unknown) => {
    if (isCurrent(generation, projectId)) {
      set({ loading: false, error: backendErrorMessage(error) });
    }
  };

  const reloadAfterMutation = async (
    generation: number,
    projectId: string,
    mutationError: unknown = null,
    clearMigration = false,
  ) => {
    try {
      const status = await getOkfStatus(projectId);
      if (isCurrent(generation, projectId)) {
        set({
          status,
          migration: clearMigration ? null : get().migration,
          loading: false,
          error: mutationError ? backendErrorMessage(mutationError) : null,
        });
      }
    } catch (refreshError) {
      fail(generation, projectId, mutationError ?? refreshError);
    }
  };

  const beginMutation = (projectId: string): number => {
    activeMutationProjectId = projectId;
    return begin(projectId);
  };

  const finishMutation = async (projectId: string) => {
    if (activeMutationProjectId === projectId) activeMutationProjectId = null;
    if (queuedRefreshProjectId === projectId) {
      queuedRefreshProjectId = null;
      const mutationError = get().error;
      await get().refresh(projectId);
      if (mutationError && get().projectId === projectId) set({ error: mutationError });
    }
  };

  return {
    projectId: null,
    status: null,
    migration: null,
    loading: false,
    error: null,
    dialogOpen: false,

    setDialogOpen: (dialogOpen) => set({ dialogOpen }),

    refresh: async (projectId) => {
      if (activeMutationProjectId === projectId) {
        queuedRefreshProjectId = projectId;
        return;
      }
      const generation = begin(projectId);
      try {
        const status = await getOkfStatus(projectId);
        if (isCurrent(generation, projectId)) set({ status, loading: false });
      } catch (error) {
        fail(generation, projectId, error);
      }
    },

    connect: async (projectId, remoteUrl, baseBranch) => {
      const generation = beginMutation(projectId);
      try {
        const status = await connectOkf(projectId, remoteUrl, baseBranch);
        if (isCurrent(generation, projectId)) set({ status, migration: null, loading: false });
      } catch (error) {
        fail(generation, projectId, error);
      } finally {
        await finishMutation(projectId);
      }
    },

    dryRun: async (projectId) => {
      const generation = beginMutation(projectId);
      try {
        const migration = await dryRunOkfMigration(projectId);
        if (isCurrent(generation, projectId)) set({ migration, loading: false });
      } catch (error) {
        fail(generation, projectId, error);
      } finally {
        await finishMutation(projectId);
      }
    },

    prepare: async (projectId) => {
      const generation = beginMutation(projectId);
      try {
        await prepareOkfMigration(projectId);
        const status = await getOkfStatus(projectId);
        if (isCurrent(generation, projectId)) set({ status, loading: false });
      } catch (error) {
        fail(generation, projectId, error);
      } finally {
        await finishMutation(projectId);
      }
    },

    approve: async (projectId) => {
      const generation = beginMutation(projectId);
      let mutationError: unknown = null;
      try {
        const changesetId = get().status?.pending?.id;
        if (!changesetId) throw new Error("No pending OKF changeset to approve.");
        await approveOkfChangeset(projectId, changesetId);
      } catch (error) {
        mutationError = error;
      }
      await reloadAfterMutation(generation, projectId, mutationError, true);
      await finishMutation(projectId);
    },

    sync: async (projectId) => {
      const generation = beginMutation(projectId);
      let mutationError: unknown = null;
      try {
        await syncOkf(projectId);
      } catch (error) {
        mutationError = error;
      }
      await reloadAfterMutation(generation, projectId, mutationError);
      await finishMutation(projectId);
    },

    resolve: async (projectId, resolution) => {
      const generation = beginMutation(projectId);
      let mutationError: unknown = null;
      try {
        const current = get().status;
        const changesetId = current?.pending?.id ?? current?.conflicts[0]?.changesetId;
        if (!changesetId) throw new Error("No conflicted OKF changeset to resolve.");
        await resolveOkfConflict(projectId, changesetId, resolution);
      } catch (error) {
        mutationError = error;
      }
      await reloadAfterMutation(generation, projectId, mutationError);
      await finishMutation(projectId);
    },

    reset: () => {
      requestGeneration += 1;
      activeMutationProjectId = null;
      queuedRefreshProjectId = null;
      set({
        projectId: null,
        status: null,
        migration: null,
        loading: false,
        error: null,
        dialogOpen: false,
      });
    },
  };
});

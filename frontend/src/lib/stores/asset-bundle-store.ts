import { create } from "zustand";
import {
  listAssetBundles,
  getAssetBundle,
  getAssetBundleById,
  uploadAssetBundle,
  deleteAssetBundle,
  deleteAssetBundleById,
  fetchAssetBundleFile,
  fetchAssetBundleFileById,
  ApiError,
  type AssetBundleListItem,
  type AssetBundleDetail,
  type StepType,
  type WizardOptionCatalog,
} from "@/lib/api";
import {
  diffMissingTriples,
  getAllPossibleTriples,
  type BundleTriple,
} from "@/lib/asset-bundles/possible-triples";

interface LoadedFile {
  path: string;
  contentType: string;
  text?: string;
  blobUrl?: string;
}

interface AssetBundleState {
  bundles: AssetBundleListItem[];
  selectedBundleId: string | null;
  selectedBundle: AssetBundleDetail | null;
  selectedFilePath: string | null;
  loadedFile: LoadedFile | null;
  loading: boolean;
  uploading: boolean;
  error: string | null;
  errorStatus: number | null;
  filterStep: StepType | "GLOBAL" | "ALL";

  load: () => Promise<void>;
  setFilter: (step: StepType | "GLOBAL" | "ALL") => void;
  select: (id: string | null) => Promise<void>;
  selectFile: (relativePath: string | null) => Promise<void>;
  upload: (file: File) => Promise<void>;
  delete: (id: string) => Promise<void>;
  clearError: () => void;

  // Coverage-View
  activeTab: "uploaded" | "missing";
  selectedMissingTripleId: string | null;

  setActiveTab: (tab: "uploaded" | "missing") => void;
  selectMissingTriple: (id: string | null) => void;
  getMissingTriples: (catalog?: WizardOptionCatalog | null) => BundleTriple[];
}

export const useAssetBundleStore = create<AssetBundleState>((set, get) => ({
  bundles: [],
  selectedBundleId: null,
  selectedBundle: null,
  selectedFilePath: null,
  loadedFile: null,
  loading: false,
  uploading: false,
  error: null,
  errorStatus: null,
  filterStep: "ALL",

  async load() {
    set({ loading: true, error: null, errorStatus: null });
    try {
      const bundles = await listAssetBundles();
      set({ bundles, loading: false, errorStatus: null });
    } catch (e) {
      set({
        error: (e as Error).message,
        errorStatus: e instanceof ApiError ? e.status : null,
        loading: false,
      });
    }
  },

  setFilter(step) {
    set({ filterStep: step });
  },

  async select(id) {
    if (id === null) {
      set({ selectedBundleId: null, selectedBundle: null, selectedFilePath: null, loadedFile: null });
      return;
    }
    const bundle = get().bundles.find((b) => b.id === id);
    if (!bundle) return;
    set({ selectedBundleId: id, selectedBundle: null, selectedFilePath: null, loadedFile: null });
    try {
      const detail = bundle.scope === "GLOBAL"
        ? await getAssetBundleById(bundle.id)
        : await getAssetBundle(bundle.step as StepType, bundle.field as string, bundle.value as string);
      set({ selectedBundle: detail });
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },

  async selectFile(relativePath) {
    const previousBlob = get().loadedFile?.blobUrl;
    if (previousBlob) URL.revokeObjectURL(previousBlob);

    if (relativePath === null) {
      set({ selectedFilePath: null, loadedFile: null });
      return;
    }
    const bundle = get().selectedBundle;
    if (!bundle) return;
    const fileEntry = bundle.files.find((f) => f.relativePath === relativePath);
    if (!fileEntry) return;

    set({ selectedFilePath: relativePath, loadedFile: null });
    try {
      const res = bundle.manifest.scope === "GLOBAL"
        ? await fetchAssetBundleFileById(bundle.manifest.id, relativePath)
        : await fetchAssetBundleFile(
            bundle.manifest.step as StepType,
            bundle.manifest.field as string,
            bundle.manifest.value as string,
            relativePath,
          );
      if (!res.ok) throw new Error(`Load failed: ${res.status}`);

      const ct = fileEntry.contentType;
      const isText = ct.startsWith("text/") || ct === "application/json"
        || ct === "application/yaml" || ct === "application/typescript"
        || ct === "application/javascript";

      if (isText) {
        const text = await res.text();
        set({ loadedFile: { path: relativePath, contentType: ct, text } });
      } else {
        const blob = await res.blob();
        const blobUrl = URL.createObjectURL(blob);
        set({ loadedFile: { path: relativePath, contentType: ct, blobUrl } });
      }
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },

  async upload(file) {
    set({ uploading: true, error: null });
    try {
      const result = await uploadAssetBundle(file);
      const bundles = await listAssetBundles();
      set({ bundles, uploading: false });
      await get().select(result.manifest.id);
    } catch (e) {
      set({ error: (e as Error).message, uploading: false });
    }
  },

  async delete(id) {
    set({ error: null });
    try {
      const bundle = get().bundles.find((b) => b.id === id) ?? get().selectedBundle?.manifest;
      if (!bundle) throw new Error("Bundle not found");
      if (bundle.scope === "GLOBAL") {
        await deleteAssetBundleById(bundle.id);
      } else {
        await deleteAssetBundle(bundle.step as StepType, bundle.field as string, bundle.value as string);
      }
      const bundles = await listAssetBundles();
      set({ bundles, selectedBundleId: null, selectedBundle: null, selectedFilePath: null, loadedFile: null });
    } catch (e) {
      set({ error: (e as Error).message });
      throw e;
    }
  },

  clearError() {
    set({ error: null, errorStatus: null });
  },

  // Coverage-View
  activeTab: "uploaded",
  selectedMissingTripleId: null,

  setActiveTab(tab) {
    set({ activeTab: tab });
  },

  selectMissingTriple(id) {
    set({ selectedMissingTripleId: id });
  },

  getMissingTriples(catalog) {
    return diffMissingTriples(getAllPossibleTriples(catalog), get().bundles);
  },
}));

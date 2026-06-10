// frontend/src/components/asset-bundles/AssetBundlesPage.tsx
"use client";

import { useEffect } from "react";
import { useAssetBundleStore } from "@/lib/stores/asset-bundle-store";
import { useWizardOptionsStore } from "@/lib/stores/wizard-options-store";
import { BundleList } from "./BundleList";
import { BundleDetail } from "./BundleDetail";
import { MissingBundleList } from "./MissingBundleList";
import { ManifestStubView } from "./ManifestStubView";
import { cn } from "@/lib/utils";
import { ProtectedAreaDialog } from "@/components/auth/ProtectedAreaDialog";

export function AssetBundlesPage() {
  const load = useAssetBundleStore((s) => s.load);
  const bundles = useAssetBundleStore((s) => s.bundles);
  const activeTab = useAssetBundleStore((s) => s.activeTab);
  const setActiveTab = useAssetBundleStore((s) => s.setActiveTab);
  const getMissingTriples = useAssetBundleStore((s) => s.getMissingTriples);
  const catalog = useWizardOptionsStore((s) => s.catalog);
  const catalogLoading = useWizardOptionsStore((s) => s.loading);
  const loadCatalog = useWizardOptionsStore((s) => s.loadCatalog);
  const errorStatus = useAssetBundleStore((s) => s.errorStatus);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (!catalog && !catalogLoading) {
      void loadCatalog();
    }
  }, [catalog, catalogLoading, loadCatalog]);

  const missingCount = getMissingTriples(catalog).length;
  const protectedArea = errorStatus === 401 || errorStatus === 403;

  return (
    <div className="flex h-screen flex-col">
      <ProtectedAreaDialog open={protectedArea} />
      <header className="border-b px-6 py-4">
        <h1 className="text-xl font-semibold">Asset Bundles</h1>
        <p className="text-sm text-muted-foreground">
          Kuratierte Claude-Code Skills, Commands und Agents.
        </p>
      </header>
      <div className="flex items-center gap-2 border-b px-6 py-2">
        <TabPill
          active={activeTab === "uploaded"}
          onClick={() => setActiveTab("uploaded")}
        >
          Hochgeladen ({bundles.length})
        </TabPill>
        <TabPill
          active={activeTab === "missing"}
          onClick={() => setActiveTab("missing")}
        >
          Fehlend ({missingCount})
        </TabPill>
      </div>
      <div className="flex flex-1 min-h-0">
        <aside className="w-96 border-r overflow-hidden">
          {activeTab === "uploaded" ? <BundleList /> : <MissingBundleList catalog={catalog} />}
        </aside>
        <main className="flex-1 overflow-hidden">
          {activeTab === "uploaded" ? <BundleDetail /> : <ManifestStubView catalog={catalog} />}
        </main>
      </div>
    </div>
  );
}

function TabPill({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "rounded-full px-3 py-1 text-xs font-medium transition-colors",
        active
          ? "bg-primary text-primary-foreground"
          : "bg-muted text-muted-foreground hover:text-foreground",
      )}
    >
      {children}
    </button>
  );
}

"use client";

import { useEffect, useState } from "react";
import { getFeaturesConfig, type AppFeaturesConfig } from "@/lib/api";
import { useAuthStore } from "@/lib/stores/auth-store";
import { HackathonLockPage } from "@/components/hackathon/HackathonLockPage";

type ConfigState =
  | { status: "loading"; config: null }
  | { status: "ready"; config: AppFeaturesConfig }
  | { status: "open"; config: null };

export function HackathonLockProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<ConfigState>({ status: "loading", config: null });
  const user = useAuthStore((s) => s.user);
  const authStatus = useAuthStore((s) => s.status);

  useEffect(() => {
    let cancelled = false;

    getFeaturesConfig()
      .then((config) => {
        if (!cancelled) setState({ status: "ready", config });
      })
      .catch(() => {
        if (!cancelled) setState({ status: "open", config: null });
      });

    return () => {
      cancelled = true;
    };
  }, []);

  if (state.status === "loading") {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background text-sm uppercase tracking-[0.24em] text-muted-foreground">
        System wird synchronisiert
      </div>
    );
  }

  if (state.status === "open" || !state.config.hackathonLock?.enabled) {
    return <>{children}</>;
  }

  const lock = state.config.hackathonLock;
  if (lock.adminBypassEnabled && authStatus === "loading") {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background text-sm uppercase tracking-[0.24em] text-muted-foreground">
        Berechtigung wird geprueft
      </div>
    );
  }

  if (lock.adminBypassEnabled && user?.admin === true) {
    return <>{children}</>;
  }

  return <HackathonLockPage config={lock} />;
}

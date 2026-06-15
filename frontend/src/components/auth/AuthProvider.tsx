"use client";

import { useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useAuthStore } from "@/lib/stores/auth-store";
import { setUnauthorizedHandler } from "@/lib/api";

const PUBLIC_PATHS = ["/", "/login", "/register", "/start-screen"];
const AUTH_PATHS = ["/login", "/register"];

function matchesPath(pathname: string, paths: string[]) {
  return paths.some((path) => pathname === path || (path !== "/" && pathname.startsWith(`${path}/`)));
}

function loginUrl(pathname: string) {
  const search = typeof window !== "undefined" ? window.location.search : "";
  return `/login?next=${encodeURIComponent(`${pathname}${search}`)}`;
}

function LoadingShell() {
  return <main className="min-h-screen bg-background" />;
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const initialize = useAuthStore((s) => s.initialize);
  const clear = useAuthStore((s) => s.clear);
  const status = useAuthStore((s) => s.status);
  const pathname = usePathname() ?? "/";
  const router = useRouter();
  const isPublic = matchesPath(pathname, PUBLIC_PATHS);
  const isAuthPath = matchesPath(pathname, AUTH_PATHS);

  useEffect(() => {
    initialize();
  }, [initialize]);

  useEffect(() => {
    setUnauthorizedHandler(() => {
      clear();
      router.replace(loginUrl(pathname));
    });
    return () => setUnauthorizedHandler(null);
  }, [clear, pathname, router]);

  useEffect(() => {
    if (!isPublic && status === "guest") {
      router.replace(loginUrl(pathname));
    }
    if (isAuthPath && status === "authenticated") {
      router.replace("/projects");
    }
  }, [isAuthPath, isPublic, pathname, router, status]);

  if (!isPublic && status !== "authenticated") {
    return <LoadingShell />;
  }

  return <>{children}</>;
}

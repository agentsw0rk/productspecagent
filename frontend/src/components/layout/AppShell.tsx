"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Cpu, FolderKanban, MessageSquareText, Package, Plus, Settings, SlidersHorizontal, Sparkles } from "lucide-react";
import { cn } from "@/lib/utils";
import { LogoutButton } from "@/components/auth/LogoutButton";

interface AppShellProps {
  children: React.ReactNode;
}

interface NavItemProps {
  href: string;
  icon: React.ReactNode;
  label: string;
  active: boolean;
}

function NavItem({ href, icon, label, active }: NavItemProps) {
  return (
    <Link
      href={href}
      className={cn(
        "relative flex h-10 w-10 items-center justify-center rounded-lg transition-colors duration-150",
        active
          ? "text-sidebar-primary"
          : "text-sidebar-foreground hover:text-zinc-200"
      )}
      title={label}
      aria-label={label}
    >
      {active && (
        <div className="absolute left-0 top-1/2 -translate-y-1/2 h-5 w-[3px] rounded-r-full bg-sidebar-primary" />
      )}
      {icon}
    </Link>
  );
}

function IconRail() {
  const pathname = usePathname();
  const isWorkspace = pathname?.startsWith("/projects/") && pathname !== "/projects/new";

  return (
    <aside className="flex h-screen w-14 shrink-0 flex-col items-center bg-sidebar py-3 gap-1">
      <Link
        href="/projects"
        className="flex h-8 w-8 items-center justify-center rounded-lg bg-sidebar-primary text-sidebar-primary-foreground mb-2 shadow-sm"
        aria-label="Spec Agent Dashboard"
      >
        <Sparkles size={16} />
      </Link>

      <div className="h-px w-6 bg-sidebar-border mb-1" />

      <nav className="flex flex-col items-center gap-1 flex-1">
        <NavItem
          href="/projects"
          icon={<FolderKanban size={20} />}
          label="Projekte"
          active={pathname === "/projects"}
        />
        <NavItem
          href="/projects/new"
          icon={<Plus size={20} />}
          label="Neues Projekt"
          active={pathname === "/projects/new"}
        />
        <NavItem
          href="/asset-bundles"
          icon={<Package size={20} />}
          label="Asset Bundles"
          active={pathname?.startsWith("/asset-bundles") ?? false}
        />
        <NavItem
          href="/admin/wizard-options"
          icon={<SlidersHorizontal size={20} />}
          label="Wizard Optionen"
          active={pathname?.startsWith("/admin/wizard-options") ?? false}
        />
        <NavItem
          href="/prompts"
          icon={<MessageSquareText size={20} />}
          label="Prompts"
          active={pathname?.startsWith("/prompts") ?? false}
        />
        <NavItem
          href="/agent-models"
          icon={<Cpu size={20} />}
          label="Agent-Modelle"
          active={pathname?.startsWith("/agent-models") ?? false}
        />
      </nav>

      <div className="flex flex-col items-center gap-1">
        <LogoutButton />
        <button
          onClick={() => window.dispatchEvent(new CustomEvent("spec-agent:toggle-project-options"))}
          className="flex h-10 w-10 items-center justify-center rounded-lg text-sidebar-foreground hover:text-zinc-200 transition-colors duration-150"
          title={isWorkspace ? "Projekt-Optionen" : "Einstellungen"}
          aria-label={isWorkspace ? "Projekt-Optionen" : "Einstellungen"}
        >
          <Settings size={20} />
        </button>
      </div>
    </aside>
  );
}

export function AppShell({ children }: AppShellProps) {
  const pathname = usePathname();
  if (pathname === "/") {
    return <>{children}</>;
  }

  const isWorkspace = pathname?.startsWith("/projects/") && pathname !== "/projects/new";
  const isFullBleed =
    isWorkspace ||
    (pathname?.startsWith("/prompts") ?? false) ||
    (pathname?.startsWith("/agent-models") ?? false);

  return (
    <div className="flex h-screen bg-background">
      <IconRail />
      <main className={cn("flex-1", isFullBleed ? "overflow-hidden" : "overflow-y-auto")}>
        {children}
      </main>
    </div>
  );
}

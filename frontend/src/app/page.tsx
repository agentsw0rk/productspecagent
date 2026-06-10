"use client";

import Link from "next/link";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import {
  ArrowRight,
  Blocks,
  BrainCircuit,
  CheckCircle2,
  FileArchive,
  GitBranch,
  LayoutDashboard,
  Palette,
  Settings2,
  Sparkles,
} from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { cn } from "@/lib/utils";

const SKIP_STORAGE_KEY = "psa-start-screen-skipped";

const capabilities = [
  {
    title: "Gefuehrter Product Wizard",
    description:
      "Fuehrt von Produktidee und Vision ueber Problem, Features, MVP, Design, Architektur, Backend, Frontend und Review.",
    icon: Sparkles,
  },
  {
    title: "Kategorieabhaengige Optionen",
    description:
      "SaaS, Mobile App, CLI Tool, Library, Desktop App und API bekommen passende Schritte, Scopes und Technologieoptionen.",
    icon: Blocks,
  },
  {
    title: "AI-Agent im Kontext",
    description:
      "Der Agent reagiert auf deine Eingaben, erkennt offene Fragen, schlaegt Entscheidungen vor und bleibt im Projektkontext.",
    icon: BrainCircuit,
  },
  {
    title: "Feature-Graph",
    description:
      "Features werden mit Frontend-/Backend-Scopes, Akzeptanzkriterien und Abhaengigkeiten als umsetzbarer Graph gepflegt.",
    icon: GitBranch,
  },
  {
    title: "Agentic Design Workbench",
    description:
      "Beschreibungen und Bilder werden analysiert; daraus entstehen Design-Briefing, HTML-Preview und Export-Artefakte.",
    icon: Palette,
  },
  {
    title: "Review, Export und Handoff",
    description:
      "Der finale Review erzeugt Spec, Docs und ein Handoff-Paket fuer Coding Agents inklusive Projektkontext.",
    icon: FileArchive,
  },
  {
    title: "Project Cockpit und Living Sync",
    description:
      "Nach dem Wizard werden Features, Status, Tests, Hinweise und Rueckmeldungen aus Coding-Agent-Workflows sichtbar.",
    icon: LayoutDashboard,
  },
  {
    title: "Anpassbare Agent-Plattform",
    description:
      "Wizard-Optionen, Prompts, Agent-Modelle und Asset Bundles koennen verwaltet und an Teams angepasst werden.",
    icon: Settings2,
  },
];

const workflow = [
  "Idee beschreiben",
  "Features strukturieren",
  "Design und Technik schaerfen",
  "Review bestaetigen",
  "Handoff exportieren",
];

export default function Home() {
  const router = useRouter();

  useEffect(() => {
    if (window.localStorage.getItem(SKIP_STORAGE_KEY) === "true") {
      router.replace("/projects");
    }
  }, [router]);

  function skipStartScreen() {
    window.localStorage.setItem(SKIP_STORAGE_KEY, "true");
    router.push("/projects");
  }

  return (
    <main className="min-h-screen overflow-hidden bg-background text-foreground">
      <section className="relative min-h-screen border-b border-border/70">
        <div className="absolute inset-0 bg-[linear-gradient(to_right,hsl(var(--border)/0.32)_1px,transparent_1px),linear-gradient(to_bottom,hsl(var(--border)/0.22)_1px,transparent_1px)] bg-[size:64px_64px]" />
        <div className="absolute inset-x-0 top-0 h-72 bg-[radial-gradient(circle_at_50%_0%,hsl(var(--primary)/0.18),transparent_64%)]" />

        <div className="relative mx-auto flex min-h-screen w-full max-w-7xl flex-col px-5 py-6 sm:px-8 lg:px-10">
          <header className="flex items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <div className="flex h-10 w-10 items-center justify-center rounded-lg border border-primary/25 bg-primary/10 text-primary">
                <Sparkles size={18} />
              </div>
              <div>
                <p className="text-sm font-semibold leading-none">Product Spec Agent</p>
                <p className="mt-1 text-xs text-muted-foreground">Agentic product specification</p>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <Link href="/login" className={cn(buttonVariants({ variant: "ghost", size: "sm" }))}>
                Anmelden
              </Link>
              <Link href="/projects/new" className={cn(buttonVariants({ size: "sm" }))}>
                Loslegen
              </Link>
            </div>
          </header>

          <div className="grid flex-1 items-center gap-12 py-14 lg:grid-cols-[minmax(0,1fr)_460px] lg:py-10">
            <div className="max-w-3xl">
              <div className="inline-flex items-center gap-2 rounded-full border border-border bg-card/70 px-3 py-1 text-xs text-muted-foreground shadow-sm">
                <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" />
                Wizard, AI-Agenten, Design und Handoff in einem Flow
              </div>

              <h1 className="mt-7 max-w-3xl text-5xl font-semibold leading-[1.02] tracking-normal text-foreground sm:text-6xl lg:text-7xl">
                Product Spec Agent
              </h1>
              <p className="mt-6 max-w-2xl text-lg leading-8 text-muted-foreground sm:text-xl">
                Von der Produktidee zur agententauglichen Spezifikation. ProductSpecAgent fuehrt dich durch Idee,
                Problem, Features, Architektur, Design und Review und erzeugt daraus verwertbare Projektartefakte.
              </p>

              <div className="mt-8 flex flex-col gap-3 sm:flex-row">
                <Link href="/projects/new" className={cn(buttonVariants({ size: "lg" }), "gap-2")}>
                  Neues Projekt starten
                  <ArrowRight size={16} />
                </Link>
                <Button size="lg" variant="outline" type="button" onClick={skipStartScreen}>
                  Startseite ueberspringen
                </Button>
              </div>

              <div className="mt-10 grid max-w-2xl grid-cols-1 gap-3 text-sm text-muted-foreground sm:grid-cols-3">
                <div className="flex items-center gap-2">
                  <CheckCircle2 size={16} className="text-emerald-400" />
                  Kategorieabhaengige Schritte
                </div>
                <div className="flex items-center gap-2">
                  <CheckCircle2 size={16} className="text-emerald-400" />
                  Strukturierte Feature-Daten
                </div>
                <div className="flex items-center gap-2">
                  <CheckCircle2 size={16} className="text-emerald-400" />
                  Coding-Agent-Handoff
                </div>
              </div>
            </div>

            <div className="relative">
              <div className="rounded-lg border border-border bg-card/85 p-4 shadow-2xl shadow-black/30 backdrop-blur">
                <div className="flex items-center justify-between border-b border-border pb-3">
                  <div>
                    <p className="text-sm font-medium">Spec Flow</p>
                    <p className="text-xs text-muted-foreground">SaaS Projekt</p>
                  </div>
                  <span className="rounded-full bg-emerald-400/10 px-2 py-1 text-xs text-emerald-300">
                    bereit
                  </span>
                </div>
                <div className="space-y-3 py-4">
                  {workflow.map((item, index) => (
                    <div key={item} className="flex items-center gap-3 rounded-md border border-border/70 bg-background/70 p-3">
                      <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-primary/10 text-xs font-semibold text-primary">
                        {index + 1}
                      </div>
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium">{item}</p>
                        <div className="mt-2 h-1.5 rounded-full bg-muted">
                          <div
                            className="h-full rounded-full bg-primary"
                            style={{ width: `${92 - index * 12}%` }}
                          />
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
                <div className="rounded-md border border-cyan-400/20 bg-cyan-400/10 p-3 text-sm text-cyan-100">
                  AI-Agent erkennt offene Entscheidungen und Rueckfragen, bevor sie spaeter im Handoff teuer werden.
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section className="mx-auto w-full max-w-7xl px-5 py-16 sm:px-8 lg:px-10">
        <div className="max-w-2xl">
          <p className="text-sm font-medium text-primary">Hauptfunktionen</p>
          <h2 className="mt-3 text-3xl font-semibold tracking-normal sm:text-4xl">
            Ein Produktfluss, der fuer Agenten lesbar bleibt.
          </h2>
          <p className="mt-4 text-base leading-7 text-muted-foreground">
            ProductSpecAgent sammelt nicht nur Text. Es erzeugt strukturierte Entscheidungen, Features,
            Akzeptanzkriterien, Design-Artefakte und Handoff-Dateien, die ein Team oder Coding Agent weiterverwenden kann.
          </p>
        </div>

        <div className="mt-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {capabilities.map((item) => {
            const Icon = item.icon;
            return (
              <article key={item.title} className="rounded-lg border border-border bg-card p-5">
                <div className="flex h-10 w-10 items-center justify-center rounded-md bg-primary/10 text-primary">
                  <Icon size={18} />
                </div>
                <h3 className="mt-4 text-base font-semibold">{item.title}</h3>
                <p className="mt-2 text-sm leading-6 text-muted-foreground">{item.description}</p>
              </article>
            );
          })}
        </div>
      </section>

      <section className="border-t border-border bg-card/45">
        <div className="mx-auto grid w-full max-w-7xl gap-8 px-5 py-14 sm:px-8 lg:grid-cols-[360px_minmax(0,1fr)] lg:px-10">
          <div>
            <p className="text-sm font-medium text-primary">Projektarten</p>
            <h2 className="mt-3 text-2xl font-semibold tracking-normal">Der Wizard passt sich dem Produkt an.</h2>
          </div>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {["SaaS", "Mobile App", "CLI Tool", "Library", "Desktop App", "API"].map((category) => (
              <div key={category} className="rounded-lg border border-border bg-background p-4">
                <p className="font-medium">{category}</p>
                <p className="mt-2 text-sm leading-6 text-muted-foreground">
                  Passende Schritte, Scopes und Technologieoptionen fuer diesen Projekttyp.
                </p>
              </div>
            ))}
          </div>
        </div>
      </section>
    </main>
  );
}

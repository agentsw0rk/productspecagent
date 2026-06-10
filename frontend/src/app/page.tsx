"use client";

import Image from "next/image";
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

const screenshots = [
  {
    title: "Gefuehrter Spec Flow",
    description:
      "Wizard und Agent laufen nebeneinander: Eingaben, offene Entscheidungen und naechste Schritte bleiben sichtbar.",
    image: "/start-screen/wizard.png",
    alt: "ProductSpecAgent Wizard mit Projektidee, Kategorieauswahl und Agent-Chat",
    width: 3012,
    height: 1486,
  },
  {
    title: "Kategorieabhaengige Optionen",
    description:
      "Admin-Teams pflegen Schritte, Scopes und Auswahlwerte je Produkttyp, damit SaaS, Mobile, API und Library passend gefuehrt werden.",
    image: "/start-screen/wizard-options.png",
    alt: "Wizard-Optionen mit sichtbaren Schritten, Feature Scopes und Architektur-Auswahlwerten",
    width: 2886,
    height: 1350,
  },
  {
    title: "Asset Bundles fuer Agenten",
    description:
      "Fehlende und hochgeladene Bundles zeigen, welche Skills, Commands und Agents fuer gewaehlte Technologien bereitstehen.",
    image: "/start-screen/asset-bundles.png",
    alt: "Asset-Bundles-Ansicht mit fehlenden Microservices Bundles und Manifest-Vorschau",
    width: 3016,
    height: 1492,
  },
  {
    title: "Projekt-Cockpit",
    description:
      "Nach dem Wizard werden Feature-Status, Abhaengigkeiten, Design-Arbeit und Review-Signale in einer Arbeitsflaeche sichtbar.",
    image: "/start-screen/cockpit.png",
    alt: "Projekt-Cockpit mit Feature-Liste, Feature-Graph und Snapshot-Review",
    width: 3012,
    height: 1456,
  },
];

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
      <section className="relative min-h-[92svh] border-b border-border/70">
        <div className="absolute inset-0 bg-[linear-gradient(to_right,hsl(var(--border)/0.32)_1px,transparent_1px),linear-gradient(to_bottom,hsl(var(--border)/0.22)_1px,transparent_1px)] bg-[size:64px_64px]" />
        <div className="absolute inset-x-0 top-0 h-72 bg-[radial-gradient(circle_at_50%_0%,hsl(var(--primary)/0.18),transparent_64%)]" />

        <div className="relative mx-auto flex min-h-[92svh] w-full max-w-7xl flex-col px-5 py-6 sm:px-8 lg:px-10">
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

          <div className="grid flex-1 items-center gap-12 py-14 lg:grid-cols-[minmax(0,0.82fr)_minmax(520px,1.18fr)] lg:py-10">
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
              <div className="overflow-hidden rounded-lg border border-border bg-card/85 shadow-2xl shadow-black/30 backdrop-blur">
                <div className="flex items-center justify-between border-b border-border px-4 py-3">
                  <div>
                    <p className="text-sm font-medium">Spec Flow</p>
                    <p className="text-xs text-muted-foreground">SaaS Projekt</p>
                  </div>
                  <span className="rounded-full bg-emerald-400/10 px-2 py-1 text-xs text-emerald-300">
                    bereit
                  </span>
                </div>
                <div className="relative aspect-[3012/1486] bg-muted">
                  <Image
                    src="/start-screen/wizard.png"
                    alt="ProductSpecAgent Wizard mit Projektidee, Kategorieauswahl und Agent-Chat"
                    fill
                    priority
                    sizes="(max-width: 1024px) 100vw, 620px"
                    className="object-cover"
                  />
                </div>
                <div className="border-t border-cyan-400/20 bg-cyan-400/10 px-4 py-3 text-sm text-cyan-100">
                  AI-Agent erkennt offene Entscheidungen und Rueckfragen direkt im Wizard-Kontext.
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section className="mx-auto w-full max-w-7xl px-5 py-16 sm:px-8 lg:px-10">
        <div className="grid gap-8 lg:grid-cols-[360px_minmax(0,1fr)]">
          <div>
            <p className="text-sm font-medium text-primary">Produkt in Aktion</p>
            <h2 className="mt-3 text-3xl font-semibold tracking-normal sm:text-4xl">
              Reale Arbeitsflaechen statt abstrakter Versprechen.
            </h2>
            <p className="mt-4 text-base leading-7 text-muted-foreground">
              Die wichtigsten Funktionen sind als echte Screens sichtbar: Wizard, Optionen, Agent-Bundles und Cockpit.
            </p>
          </div>

          <div className="grid gap-4">
            {screenshots.map((item, index) => (
              <article
                key={item.title}
                className={cn(
                  "overflow-hidden rounded-lg border border-border bg-card",
                  index === 0 ? "lg:grid lg:grid-cols-[minmax(0,1fr)_280px]" : "sm:grid sm:grid-cols-[minmax(0,1fr)_260px]",
                )}
              >
                <div className="relative aspect-[2.03/1] bg-muted">
                  <Image
                    src={item.image}
                    alt={item.alt}
                    width={item.width}
                    height={item.height}
                    sizes="(max-width: 1024px) 100vw, 720px"
                    className="h-full w-full object-cover"
                  />
                </div>
                <div className="flex flex-col justify-center p-5">
                  <p className="text-sm font-semibold">{item.title}</p>
                  <p className="mt-2 text-sm leading-6 text-muted-foreground">{item.description}</p>
                </div>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section className="mx-auto w-full max-w-7xl border-t border-border px-5 py-16 sm:px-8 lg:px-10">
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

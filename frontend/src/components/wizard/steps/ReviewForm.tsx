"use client";

import { CheckCircle2, Layers, Monitor, Server, Sparkles } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { useWizardStore, selectFeatures } from "@/lib/stores/wizard-store";
import type { WizardFeature } from "@/lib/api";

function text(value: unknown): string {
  if (typeof value === "string") return value.trim();
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return "";
}

function list(value: unknown): string[] {
  return Array.isArray(value) ? value.map(text).filter(Boolean) : [];
}

function SummarySection({
  title,
  icon,
  children,
}: {
  title: string;
  icon: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-md border border-border bg-card/60 p-4">
      <div className="mb-3 flex items-center gap-2">
        <div className="flex h-7 w-7 items-center justify-center rounded-full bg-primary/10 text-primary">
          {icon}
        </div>
        <h2 className="text-sm font-semibold text-foreground">{title}</h2>
      </div>
      {children}
    </section>
  );
}

function FieldRow({ label, value }: { label: string; value: unknown }) {
  const rendered = Array.isArray(value) ? list(value).join(", ") : text(value);
  if (!rendered) return null;
  return (
    <div className="grid gap-1 border-t border-border/70 py-2 first:border-t-0 sm:grid-cols-[150px_1fr]">
      <div className="text-xs font-medium text-muted-foreground">{label}</div>
      <div className="text-sm text-foreground">{rendered}</div>
    </div>
  );
}

function FeatureSummary({ features }: { features: WizardFeature[] }) {
  if (features.length === 0) {
    return <p className="text-sm text-muted-foreground">Keine Features erfasst.</p>;
  }

  return (
    <div className="space-y-3">
      {features.map((feature) => (
        <div key={feature.id} className="rounded-md border border-border/70 p-3">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-sm font-semibold text-foreground">{feature.title || "Unbenanntes Feature"}</h3>
            {list(feature.scopes).map((scope) => (
              <Badge key={scope} variant="secondary">{scope}</Badge>
            ))}
          </div>
          {feature.description && (
            <p className="mt-2 text-sm text-muted-foreground">{feature.description}</p>
          )}
          {feature.acceptanceCriteria?.length > 0 && (
            <ul className="mt-2 list-disc space-y-1 pl-5 text-xs text-muted-foreground">
              {feature.acceptanceCriteria.map((criterion) => (
                <li key={criterion.id}>{criterion.text}</li>
              ))}
            </ul>
          )}
        </div>
      ))}
    </div>
  );
}

export function ReviewForm() {
  const { data, visibleSteps } = useWizardStore();
  const features = useWizardStore(selectFeatures);
  const visible = new Set(visibleSteps().map((step) => step.key));
  const idea = data?.steps.IDEA?.fields ?? {};
  const problem = data?.steps.PROBLEM?.fields ?? {};
  const mvp = data?.steps.MVP?.fields ?? {};
  const design = data?.steps.DESIGN?.fields ?? {};
  const architecture = data?.steps.ARCHITECTURE?.fields ?? {};
  const backend = data?.steps.BACKEND?.fields ?? {};
  const frontend = data?.steps.FRONTEND?.fields ?? {};

  const selectedMvpFeatures = list(mvp.mvpFeatures)
    .map((id) => features.find((feature) => feature.id === id)?.title ?? id);

  return (
    <div className="mx-auto max-w-4xl space-y-4">
      <div className="rounded-md border border-primary/20 bg-primary/5 p-4">
        <div className="flex items-start gap-3">
          <CheckCircle2 className="mt-0.5 h-5 w-5 text-primary" />
          <div>
            <h2 className="text-sm font-semibold text-foreground">Finaler Review</h2>
            <p className="mt-1 text-sm text-muted-foreground">
              Pruefe die Zusammenfassung. Wenn etwas fehlt, springe ueber die Step-Navigation zurueck.
            </p>
          </div>
        </div>
      </div>

      <SummarySection title="Idee" icon={<Sparkles className="h-4 w-4" />}>
        <FieldRow label="Produktname" value={idea.productName} />
        <FieldRow label="Kategorie" value={idea.category} />
        <FieldRow label="Vision" value={idea.vision} />
      </SummarySection>

      <SummarySection title="Problem & Zielgruppe" icon={<Layers className="h-4 w-4" />}>
        <FieldRow label="Kernproblem" value={problem.coreProblem} />
        <FieldRow label="Zielgruppe" value={problem.primaryAudience} />
        <FieldRow label="Pain Points" value={problem.painPoints} />
      </SummarySection>

      <SummarySection title="Features" icon={<Layers className="h-4 w-4" />}>
        <FeatureSummary features={features} />
      </SummarySection>

      <SummarySection title="MVP" icon={<CheckCircle2 className="h-4 w-4" />}>
        <FieldRow label="Ziel" value={mvp.goal} />
        <FieldRow label="MVP Features" value={selectedMvpFeatures} />
        <FieldRow label="Erfolgskriterien" value={mvp.successCriteria} />
      </SummarySection>

      {visible.has("DESIGN") && (
        <SummarySection title="Design" icon={<Monitor className="h-4 w-4" />}>
          <FieldRow label="Beschreibung" value={design.description} />
          <FieldRow label="Aktives Design" value={design.activeDesignTitle} />
        </SummarySection>
      )}

      {visible.has("ARCHITECTURE") && (
        <SummarySection title="Architektur" icon={<Server className="h-4 w-4" />}>
          <FieldRow label="Architektur" value={architecture.architecture} />
          <FieldRow label="Datenbank" value={architecture.database} />
          <FieldRow label="Deployment" value={architecture.deployment} />
        </SummarySection>
      )}

      {visible.has("BACKEND") && (
        <SummarySection title="Backend" icon={<Server className="h-4 w-4" />}>
          <FieldRow label="Framework" value={backend.framework} />
          <FieldRow label="API Stil" value={backend.apiStyle} />
          <FieldRow label="Auth" value={backend.auth} />
        </SummarySection>
      )}

      {visible.has("FRONTEND") && (
        <SummarySection title="Frontend" icon={<Monitor className="h-4 w-4" />}>
          <FieldRow label="Framework" value={frontend.framework} />
          <FieldRow label="UI Library" value={frontend.uiLibrary} />
          <FieldRow label="Styling" value={frontend.styling} />
          <FieldRow label="Theme" value={frontend.theme} />
        </SummarySection>
      )}
    </div>
  );
}

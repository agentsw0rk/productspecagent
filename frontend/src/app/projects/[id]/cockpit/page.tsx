"use client";

import { use, useEffect, useState } from "react";
import Link from "next/link";
import { Loader2 } from "lucide-react";
import { CockpitLockedNotice } from "@/components/cockpit/CockpitLockedNotice";
import { ProjectCockpitPrototype } from "@/components/cockpit/ProjectCockpitPrototype";
import { getProject, getWizardData, type FlowState } from "@/lib/api";
import { toCockpitFeatures, type CockpitFeature } from "@/lib/cockpit-feature-data";
import { isCockpitReady } from "@/lib/project-cockpit";

interface PageProps {
  params: Promise<{ id: string }>;
}

export default function ProjectCockpitPage({ params }: PageProps) {
  const { id } = use(params);

  return <ProjectCockpitPageContent key={id} id={id} />;
}

interface ProjectCockpitPageContentProps {
  id: string;
}

function ProjectCockpitPageContent({ id }: ProjectCockpitPageContentProps) {
  const [projectLoading, setProjectLoading] = useState(true);
  const [projectError, setProjectError] = useState<string | null>(null);
  const [flowState, setFlowState] = useState<FlowState | null>(null);
  const [wizardError, setWizardError] = useState<string | null>(null);
  const [cockpitFeatures, setCockpitFeatures] = useState<CockpitFeature[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    async function loadCockpit() {
      try {
        const response = await getProject(id);
        if (cancelled) return;

        setFlowState(response.flowState);

        if (isCockpitReady(response.flowState)) {
          try {
            const wizardData = await getWizardData(id);
            if (cancelled) return;
            setCockpitFeatures(toCockpitFeatures(wizardData));
          } catch (error: unknown) {
            if (cancelled) return;
            setWizardError(error instanceof Error ? error.message : "Failed to load wizard data");
            setCockpitFeatures([]);
          }
        } else {
          setCockpitFeatures([]);
        }

        if (!cancelled) {
          setProjectLoading(false);
        }
      } catch (error: unknown) {
        if (cancelled) return;
        setProjectError(error instanceof Error ? error.message : "Failed to load");
        setProjectLoading(false);
      }
    }

    void loadCockpit();

    return () => {
      cancelled = true;
    };
  }, [id]);

  if (projectLoading) {
    return (
      <div className="flex h-full items-center justify-center bg-background">
        <Loader2 size={24} className="animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (projectError) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 bg-background px-6 text-center">
        <p className="text-sm text-destructive">{projectError}</p>
        <Link href="/projects" className="text-sm text-primary hover:underline">
          Zurueck zu Projekten
        </Link>
      </div>
    );
  }

  if (!isCockpitReady(flowState)) {
    return <CockpitLockedNotice projectId={id} />;
  }

  if (wizardError) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 bg-background px-6 text-center">
        <p className="text-sm text-destructive">{wizardError}</p>
        <Link href={`/projects/${id}`} className="text-sm text-primary hover:underline">
          Zurueck zum Workspace
        </Link>
      </div>
    );
  }

  return <ProjectCockpitPrototype projectId={id} initialFeatures={cockpitFeatures ?? []} />;
}

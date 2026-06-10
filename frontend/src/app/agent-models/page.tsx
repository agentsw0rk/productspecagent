"use client";
import { useCallback, useEffect, useState } from "react";
import { listAgentModels, type AgentModelInfo } from "@/lib/api";
import { AgentModelList } from "@/components/agent-models/AgentModelList";
import { AgentModelDetail } from "@/components/agent-models/AgentModelDetail";
import { ProtectedAreaDialog } from "@/components/auth/ProtectedAreaDialog";
import { isProtectedAreaError } from "@/lib/protected-area";

export default function AgentModelsPage() {
  const [items, setItems] = useState<AgentModelInfo[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [reloadTick, setReloadTick] = useState(0);
  const [dirty, setDirty] = useState(false);
  const [protectedArea, setProtectedArea] = useState(false);

  useEffect(() => {
    listAgentModels()
      .then((models) => {
        setItems(models);
        setProtectedArea(false);
      })
      .catch((error) => {
        setItems([]);
        setProtectedArea(isProtectedAreaError(error));
      });
  }, [reloadTick]);

  const handleSelect = useCallback(
    (id: string) => {
      if (id === selectedId) return;
      if (dirty && !window.confirm("Änderungen verwerfen?")) return;
      setDirty(false);
      setSelectedId(id);
    },
    [selectedId, dirty],
  );

  return (
    <div className="h-full flex flex-col">
      <ProtectedAreaDialog open={protectedArea} />
      <div className="px-8 py-6 border-b">
        <h1 className="text-xl font-semibold">Agent-Modelle</h1>
        <p className="text-sm text-muted-foreground mt-1">
          Wähle pro Agent das verwendete OpenAI-Modell. Änderungen werden direkt nach dem Speichern wirksam.
        </p>
      </div>
      <div className="flex-1 grid grid-cols-[320px_1fr] min-h-0">
        <AgentModelList
          items={items}
          selectedId={selectedId}
          onSelect={handleSelect}
        />
        <div className="border-l overflow-y-auto">
          {selectedId ? (
            <AgentModelDetail
              key={selectedId}
              agentId={selectedId}
              onChange={() => setReloadTick((t) => t + 1)}
              onDirtyChange={setDirty}
            />
          ) : (
            <div className="h-full flex items-center justify-center text-sm text-muted-foreground">
              Wähle einen Agent aus der Liste, um sein Modell zu konfigurieren.
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

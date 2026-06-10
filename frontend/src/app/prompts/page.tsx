"use client";
import { useCallback, useEffect, useState } from "react";
import { listPrompts, type PromptListItem } from "@/lib/api";
import { PromptList } from "@/components/prompts/PromptList";
import { PromptDetail } from "@/components/prompts/PromptDetail";
import { ProtectedAreaDialog } from "@/components/auth/ProtectedAreaDialog";
import { isProtectedAreaError } from "@/lib/protected-area";

export default function PromptsPage() {
  const [items, setItems] = useState<PromptListItem[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [reloadTick, setReloadTick] = useState(0);
  const [dirty, setDirty] = useState(false);
  const [protectedArea, setProtectedArea] = useState(false);

  useEffect(() => {
    listPrompts()
      .then((prompts) => {
        setItems(prompts);
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
        <h1 className="text-xl font-semibold">Prompts</h1>
        <p className="text-sm text-muted-foreground mt-1">
          Bearbeite die System-Prompts der KI-Agents. Änderungen werden direkt nach dem Speichern wirksam.
        </p>
      </div>
      <div className="flex-1 grid grid-cols-[320px_1fr] min-h-0">
        <PromptList
          items={items}
          selectedId={selectedId}
          onSelect={handleSelect}
        />
        <div className="border-l overflow-y-auto">
          {selectedId ? (
            <PromptDetail
              key={selectedId}
              id={selectedId}
              onChange={() => setReloadTick((t) => t + 1)}
              onDirtyChange={setDirty}
            />
          ) : (
            <div className="h-full flex items-center justify-center text-sm text-muted-foreground">
              Wähle einen Prompt aus der Liste, um ihn zu bearbeiten.
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

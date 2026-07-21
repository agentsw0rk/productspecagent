import type { OkfFileOperation } from "@/lib/api";

const OPERATION_LABELS: Record<OkfFileOperation["type"], string> = {
  ADD: "Neu",
  UPDATE: "Geaendert",
  RENAME: "Umbenannt",
  DELETE: "Geloescht",
};

export function OkfDiffView({ operations }: { operations: OkfFileOperation[] }) {
  if (operations.length === 0) {
    return <p className="py-5 text-center text-xs text-muted-foreground">Keine Dateiänderungen.</p>;
  }

  return (
    <div className="space-y-3">
      {[...operations]
        .sort((a, b) => a.path.localeCompare(b.path))
        .map((operation) => (
          <section
            key={`${operation.type}:${operation.path}`}
            className="overflow-hidden rounded-md border border-border"
          >
            <header className="flex min-w-0 items-center gap-2 border-b border-border bg-muted/40 px-3 py-2 text-xs">
              <span className="shrink-0 font-medium text-primary">
                {OPERATION_LABELS[operation.type]}
              </span>
              <code className="min-w-0 truncate text-muted-foreground">
                {operation.previousPath ? `${operation.previousPath} -> ` : ""}
                {operation.path}
              </code>
            </header>
            <div className="grid min-w-0 grid-cols-1 divide-y divide-border md:grid-cols-2 md:divide-x md:divide-y-0">
              <div className="min-w-0">
                <div className="border-b border-border px-3 py-1.5 text-[11px] font-medium text-muted-foreground">
                  Basis
                </div>
                <pre className="max-h-64 min-h-24 overflow-auto whitespace-pre-wrap break-words p-3 font-mono text-xs leading-5">
                  {operation.baseContent ?? ""}
                </pre>
              </div>
              <div className="min-w-0">
                <div className="border-b border-border px-3 py-1.5 text-[11px] font-medium text-muted-foreground">
                  Vorschlag
                </div>
                <pre className="max-h-64 min-h-24 overflow-auto whitespace-pre-wrap break-words p-3 font-mono text-xs leading-5">
                  {operation.content ?? ""}
                </pre>
              </div>
            </div>
          </section>
        ))}
    </div>
  );
}

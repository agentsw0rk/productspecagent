"use client";
import { useEffect, useMemo, useRef, useState } from "react";
import { ChevronDown, ChevronUp, Loader2, Plus, Sparkles, Trash2 } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Button } from "@/components/ui/button";
import type { AcceptanceCriterion, FeatureScope, WizardFeature } from "@/lib/api";
import { proposeAcceptanceCriteria } from "@/lib/api";
import { SCOPE_FIELD_LABELS, SCOPE_FIELDS_BY_SCOPE } from "@/lib/step-field-labels";

interface FeatureEditDialogProps {
  feature: WizardFeature | null;
  allowedScopes: FeatureScope[];
  open: boolean;
  projectId: string;
  mode?: "edit" | "create";
  onClose: () => void;
  onSave: (patch: Partial<WizardFeature>) => void | Promise<void>;
  onDelete?: () => void;
}

type DraftFeature = Pick<WizardFeature,
  "title" | "description" | "scopes" | "scopeFields" | "acceptanceCriteria">;

function scopeLabel(scope: FeatureScope | "CORE") {
  if (scope === "FRONTEND") return "Frontend";
  if (scope === "BACKEND") return "Backend";
  return "Allgemein";
}

function snapshot(f: WizardFeature): DraftFeature {
  return {
    title: f.title,
    description: f.description,
    scopes: [...f.scopes],
    scopeFields: { ...f.scopeFields },
    // Defensive: legacy WizardFeature JSON (pre-Feature-44) lacks the field.
    acceptanceCriteria: (f.acceptanceCriteria ?? []).map((c) => ({ ...c })),
  };
}

function equalDraft(a: DraftFeature, b: DraftFeature): boolean {
  if (a.title !== b.title || a.description !== b.description) return false;
  if (a.scopes.length !== b.scopes.length) return false;
  for (const s of a.scopes) if (!b.scopes.includes(s)) return false;
  const ak = Object.keys(a.scopeFields);
  const bk = Object.keys(b.scopeFields);
  if (ak.length !== bk.length) return false;
  for (const k of ak) if (a.scopeFields[k] !== b.scopeFields[k]) return false;
  if (a.acceptanceCriteria.length !== b.acceptanceCriteria.length) return false;
  for (let i = 0; i < a.acceptanceCriteria.length; i++) {
    const x = a.acceptanceCriteria[i];
    const y = b.acceptanceCriteria[i];
    if (x.id !== y.id || x.text !== y.text) return false;
  }
  return true;
}

interface AcceptanceCriteriaListProps {
  value: AcceptanceCriterion[];
  onChange: (next: AcceptanceCriterion[]) => void;
  onPropose: () => void;
  canPropose: boolean;
  isProposing: boolean;
  proposeError: string | null;
  expanded: boolean;
  onToggleExpanded: () => void;
}

function AcceptanceCriteriaList({
  value,
  onChange,
  onPropose,
  canPropose,
  isProposing,
  proposeError,
  expanded,
  onToggleExpanded,
}: AcceptanceCriteriaListProps) {
  const inputRefs = useRef<Record<string, HTMLInputElement | null>>({});
  const focusIdRef = useRef<string | null>(null);

  useEffect(() => {
    const id = focusIdRef.current;
    if (id && inputRefs.current[id]) {
      inputRefs.current[id]?.focus();
      focusIdRef.current = null;
    }
  }, [value]);

  function patchText(id: string, val: string) {
    onChange(value.map((c) => (c.id === id ? { ...c, text: val } : c)));
  }

  function appendNew(afterId?: string) {
    const newItem: AcceptanceCriterion = {
      id: crypto.randomUUID(),
      text: "",
    };
    if (!afterId) {
      onChange([...value, newItem]);
    } else {
      const idx = value.findIndex((c) => c.id === afterId);
      const next = [...value];
      next.splice(idx + 1, 0, newItem);
      onChange(next);
    }
    focusIdRef.current = newItem.id;
  }

  function remove(id: string) {
    onChange(value.filter((c) => c.id !== id));
  }

  function move(id: string, direction: -1 | 1) {
    const idx = value.findIndex((c) => c.id === id);
    const target = idx + direction;
    if (target < 0 || target >= value.length) return;
    const next = [...value];
    [next[idx], next[target]] = [next[target], next[idx]];
    onChange(next);
  }

  return (
    <section className="rounded-xl border bg-muted/20">
      <div className={`flex flex-col gap-3 px-4 py-4 sm:flex-row sm:items-start sm:justify-between ${expanded ? "border-b" : ""}`}>
        <div className="min-w-0 flex-1 space-y-1">
          <Label>Akzeptanzkriterien</Label>
          <p className="text-sm text-muted-foreground">
            Formuliere sichtbare Done-Bedingungen. Leere Einträge werden beim Speichern entfernt.
          </p>
        </div>
        <div className="flex items-center gap-2 sm:pl-4">
          {canPropose && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={onPropose}
              disabled={isProposing || !expanded}
              className="border-primary/40 bg-primary text-primary-foreground hover:bg-primary/90 hover:text-primary-foreground"
            >
              {isProposing ? (
                <Loader2 className="mr-1 animate-spin" size={14} />
              ) : (
                <Sparkles className="mr-1" size={14} />
              )}
              {isProposing ? "Generiere..." : "Vorschlagen"}
            </Button>
          )}
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => appendNew()}
            disabled={!expanded}
            className="border-primary/40 bg-primary text-primary-foreground hover:bg-primary/90 hover:text-primary-foreground"
          >
            <Plus size={14} className="mr-1" /> Hinzufügen
          </Button>
          <Button
            type="button"
            variant="outline"
            size="icon-sm"
            onClick={onToggleExpanded}
            aria-expanded={expanded}
            aria-label={expanded ? "Akzeptanzkriterien einklappen" : "Akzeptanzkriterien ausklappen"}
            className="border-primary/40 bg-primary text-primary-foreground hover:bg-primary/90 hover:text-primary-foreground"
          >
            {expanded ? (
              <ChevronUp className="shrink-0" size={16} />
            ) : (
              <ChevronDown className="shrink-0" size={16} />
            )}
          </Button>
        </div>
      </div>

      {expanded && proposeError && (
        <div className="px-4 pt-4">
          <p className="rounded-md border border-destructive/20 bg-destructive/5 px-3 py-2 text-xs text-destructive">
            {proposeError}
          </p>
        </div>
      )}

      {expanded && (
      <div className="space-y-3 px-4 py-4">
        {value.length === 0 ? (
          <div className="rounded-lg border border-dashed bg-muted/40 px-4 py-8 text-center">
            <p className="text-sm font-medium">Noch keine Akzeptanzkriterien definiert</p>
            <p className="mt-1 text-sm text-muted-foreground">
              Ergänze eigene Kriterien oder lass dir erste Vorschläge generieren.
            </p>
          </div>
        ) : (
          value.map((c, idx) => (
            <div
              key={c.id}
              className="grid gap-3 rounded-lg border bg-muted/40 p-3 md:grid-cols-[auto_minmax(0,1fr)_auto] md:items-start"
            >
              <div className="flex items-center gap-3 md:pt-1">
                <div className="flex size-7 shrink-0 items-center justify-center rounded-full bg-secondary text-xs font-semibold text-secondary-foreground">
                  {idx + 1}
                </div>
                <span className="text-xs text-muted-foreground md:hidden">Kriterium</span>
              </div>

              <div className="space-y-2">
                <Input
                  id={`ac-text-${c.id}`}
                  aria-label={`Akzeptanzkriterium ${idx + 1}`}
                  ref={(el: HTMLInputElement | null) => { inputRefs.current[c.id] = el; }}
                  value={c.text}
                  onChange={(e) => patchText(c.id, e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      e.preventDefault();
                      appendNew(c.id);
                    }
                  }}
                  className="w-full"
                  placeholder="z. B. Aufruf von / ohne Cookie → automatisch redirect zu /login"
                />
                <p className="text-xs text-muted-foreground">
                  Enter fügt direkt darunter ein weiteres Kriterium ein.
                </p>
              </div>

              <div className="flex shrink-0 items-center justify-end gap-1 md:pt-0.5">
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  onClick={() => move(c.id, -1)}
                  disabled={idx === 0}
                  aria-label="Nach oben"
                >
                  <ChevronUp size={14} />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  onClick={() => move(c.id, 1)}
                  disabled={idx === value.length - 1}
                  aria-label="Nach unten"
                >
                  <ChevronDown size={14} />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  onClick={() => remove(c.id)}
                  aria-label="Entfernen"
                >
                  <Trash2 size={14} />
                </Button>
              </div>
            </div>
          ))
        )}
      </div>
      )}
    </section>
  );
}

export function FeatureEditDialog({
  feature,
  allowedScopes,
  open,
  projectId,
  mode = "edit",
  onClose,
  onSave,
  onDelete,
}: FeatureEditDialogProps) {
  const initialRef = useRef<DraftFeature | null>(null);
  const [draft, setDraft] = useState<DraftFeature | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  useEffect(() => {
    if (open && feature) {
      const snap = snapshot(feature);
      initialRef.current = snap;
      setDraft(snap);
    }
    if (!open) {
      initialRef.current = null;
      setDraft(null);
      setSaveError(null);
      setIsSaving(false);
    }
  }, [open, feature]);

  const scopeSections = useMemo(() => {
    if (!draft) return [];
    if (allowedScopes.length === 0) {
      return [{ scope: "CORE" as const, fields: SCOPE_FIELDS_BY_SCOPE.CORE }];
    }
    return allowedScopes
      .filter((s) => draft.scopes.includes(s))
      .map((scope) => ({ scope, fields: SCOPE_FIELDS_BY_SCOPE[scope] }));
  }, [draft, allowedScopes]);

  const [isProposing, setIsProposing] = useState(false);
  const [proposeError, setProposeError] = useState<string | null>(null);
  const [scopeDetailsExpanded, setScopeDetailsExpanded] = useState(false);
  const [acceptanceCriteriaExpanded, setAcceptanceCriteriaExpanded] = useState(false);

  useEffect(() => {
    if (open) {
      setScopeDetailsExpanded(false);
      setAcceptanceCriteriaExpanded(false);
    }
  }, [open]);

  async function handlePropose() {
    if (!feature || !draft) return;
    setIsProposing(true);
    setProposeError(null);
    try {
      const proposed = await proposeAcceptanceCriteria(projectId, feature.id);
      patch("acceptanceCriteria", [...draft.acceptanceCriteria, ...proposed]);
    } catch (e) {
      setProposeError(e instanceof Error ? e.message : "Vorschlag fehlgeschlagen");
    } finally {
      setIsProposing(false);
    }
  }

  if (!draft) {
    return (
      <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
        <DialogContent />
      </Dialog>
    );
  }

  const initial = initialRef.current;
  const isDirty = initial ? !equalDraft(draft, initial) : false;
  const cleanedACTotal = draft.acceptanceCriteria.filter((criterion) => criterion.text.trim().length > 0).length;
  const activeScopeCount = draft.scopes.length;
  const hasScopeDetails = scopeSections.some((section) => section.fields.length > 0);

  function patch<K extends keyof DraftFeature>(key: K, value: DraftFeature[K]) {
    setDraft((prev) => (prev ? { ...prev, [key]: value } : prev));
  }

  function toggleScope(s: FeatureScope) {
    if (!draft) return;
    const next = draft.scopes.includes(s)
      ? draft.scopes.filter((x) => x !== s)
      : [...draft.scopes, s];
    patch("scopes", next);
  }

  function setScopeField(key: string, val: string) {
    if (!draft) return;
    patch("scopeFields", { ...draft.scopeFields, [key]: val });
  }

  function handleClose() {
    if (!draft) {
      onClose();
      return;
    }
    if (isDirty && !window.confirm("Änderungen verwerfen?")) return;
    onClose();
  }

  async function handleSave() {
    if (!draft) return;
    const cleanedAC = draft.acceptanceCriteria
      .map((c) => ({ ...c, text: c.text.trim() }))
      .filter((c) => c.text.length > 0);
    setIsSaving(true);
    setSaveError(null);
    try {
      await onSave({
        title: draft.title,
        description: draft.description,
        scopes: draft.scopes,
        scopeFields: draft.scopeFields,
        acceptanceCriteria: cleanedAC,
      });
      onClose();
    } catch (error) {
      setSaveError(error instanceof Error ? error.message : "Speichern fehlgeschlagen");
    } finally {
      setIsSaving(false);
    }
  }

  function handleDelete() {
    if (!onDelete) return;
    if (!window.confirm("Feature wirklich löschen?")) return;
    onDelete();
  }

  return (
    <Dialog open={open} onOpenChange={(o) => !o && handleClose()}>
      <DialogContent className="max-h-[88vh] overflow-y-auto bg-muted p-0 sm:max-w-5xl">
        <DialogHeader className="border-b px-6 py-5">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div className="space-y-1">
              <DialogTitle className="text-lg">{mode === "create" ? "Feature erfassen" : "Feature bearbeiten"}</DialogTitle>
              <DialogDescription>
                {draft.title.trim() || "Unbenanntes Feature"}
              </DialogDescription>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <div className="rounded-full border bg-muted/40 px-2.5 py-1 text-xs text-muted-foreground">
                {cleanedACTotal} AC
              </div>
              <div className="rounded-full border bg-muted/40 px-2.5 py-1 text-xs text-muted-foreground">
                {activeScopeCount || 0} Scopes aktiv
              </div>
              {isDirty && (
                <div className="rounded-full border border-primary/20 bg-primary/10 px-2.5 py-1 text-xs font-medium text-foreground">
                  Ungespeichert
                </div>
              )}
            </div>
          </div>
        </DialogHeader>

        <div className="grid gap-6 px-6 py-5 lg:grid-cols-[minmax(0,1.45fr)_minmax(280px,0.95fr)]">
          <div className="space-y-6">
            <section className="rounded-xl border bg-muted/20 p-4 sm:p-5">
              <div className="mb-4 space-y-1">
                <h3 className="text-sm font-semibold">Grundlagen</h3>
                <p className="text-sm text-muted-foreground">
                  Halte das Feature knapp und verständlich. Titel, Beschreibung und Scopes bilden den Kern.
                </p>
              </div>

              <div className="space-y-4">
                <div className="space-y-2">
                  <Label htmlFor="feat-title">Titel</Label>
                  <Input
                    id="feat-title"
                    value={draft.title}
                    onChange={(e) => patch("title", e.target.value)}
                    placeholder="z. B. Login Flow"
                  />
                </div>

                <div className="space-y-2">
                  <Label htmlFor="feat-desc">Beschreibung</Label>
                  <Textarea
                    id="feat-desc"
                    rows={6}
                    value={draft.description}
                    onChange={(e) => patch("description", e.target.value)}
                    placeholder="Beschreibe kurz, welches Problem das Feature löst und was für Nutzer sichtbar sein soll."
                  />
                </div>

                {allowedScopes.length > 1 && (
                  <div className="space-y-2">
                    <Label>Scopes</Label>
                    <div className="flex flex-wrap gap-2">
                      {allowedScopes.map((s) => {
                        const active = draft.scopes.includes(s);
                        return (
                          <button
                            key={s}
                            type="button"
                            onClick={() => toggleScope(s)}
                            className={`rounded-md border px-3 py-2 text-sm transition-colors ${
                              active
                                ? "border-primary/30 bg-primary/10 text-foreground"
                                : "border-border bg-muted/40 text-muted-foreground hover:bg-muted/60 hover:text-foreground"
                            }`}
                          >
                            {scopeLabel(s)}
                          </button>
                        );
                      })}
                    </div>
                    <p className="text-xs text-muted-foreground">
                      Aktiviere nur die Bereiche, für die dieses Feature eigene Anforderungen mitbringt.
                    </p>
                  </div>
                )}
              </div>
            </section>

            <AcceptanceCriteriaList
              value={draft.acceptanceCriteria}
              onChange={(next) => patch("acceptanceCriteria", next)}
              onPropose={handlePropose}
              canPropose={mode === "edit"}
              isProposing={isProposing}
              proposeError={proposeError}
              expanded={acceptanceCriteriaExpanded}
              onToggleExpanded={() => setAcceptanceCriteriaExpanded((value) => !value)}
            />
          </div>

          <div className="space-y-4">
            <section className="rounded-xl border bg-muted/20 p-4 sm:p-5">
              <button
                type="button"
                onClick={() => setScopeDetailsExpanded((value) => !value)}
                className="flex w-full items-start justify-between gap-3 text-left"
                aria-expanded={scopeDetailsExpanded}
              >
                <div className="space-y-1">
                  <h3 className="text-sm font-semibold">Scopespezifische Details</h3>
                  <p className="text-sm text-muted-foreground">
                    Ergänze hier nur Informationen, die für ausgewählte Scopes wirklich relevant sind.
                  </p>
                </div>
                <span className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-md border border-primary/40 bg-primary text-primary-foreground">
                  {scopeDetailsExpanded ? (
                    <ChevronUp className="shrink-0" size={16} />
                  ) : (
                    <ChevronDown className="shrink-0" size={16} />
                  )}
                </span>
              </button>

              {scopeDetailsExpanded && (
                <div className="mt-4">
                  {!hasScopeDetails ? (
                    <div className="rounded-lg border border-dashed bg-muted/40 px-4 py-6 text-sm text-muted-foreground">
                      Keine zusätzlichen Scope-Felder für die aktuelle Auswahl.
                    </div>
                  ) : (
                    <div className="space-y-4">
                      {scopeSections.map(({ scope, fields }) => (
                        <section key={scope} className="space-y-3 rounded-lg border bg-muted/40 p-4">
                          <div className="space-y-1">
                            <h4 className="text-sm font-medium">{scopeLabel(scope)}</h4>
                            {scopeSections.length > 1 && (
                              <p className="text-xs text-muted-foreground">
                                Zusätzliche Details für den Bereich {scopeLabel(scope)}.
                              </p>
                            )}
                          </div>

                          {fields.map((key) => (
                            <div key={key} className="space-y-2">
                              <Label htmlFor={`feat-${key}`}>{SCOPE_FIELD_LABELS[key] ?? key}</Label>
                              <Textarea
                                id={`feat-${key}`}
                                rows={3}
                                value={draft.scopeFields[key] ?? ""}
                                onChange={(e) => setScopeField(key, e.target.value)}
                              />
                            </div>
                          ))}
                        </section>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </section>

            <section className="rounded-xl border bg-muted/20 p-4 sm:p-5">
              <h3 className="text-sm font-semibold">Hinweise</h3>
              <ul className="mt-3 space-y-2 text-sm text-muted-foreground">
                {mode === "edit" && <li>AI-Vorschläge werden an die bestehende AC-Liste angehängt.</li>}
                <li>Leere Akzeptanzkriterien werden beim Speichern automatisch entfernt.</li>
                {onDelete && <li>Löschen entfernt das Feature inklusive seiner Abhängigkeiten aus dem Graphen.</li>}
              </ul>
            </section>
          </div>
        </div>

        <div className="flex flex-col gap-4 border-t bg-muted/30 px-6 py-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="space-y-1">
            {onDelete ? (
              <>
                <Button type="button" variant="ghost" onClick={handleDelete}>
                  <Trash2 size={14} className="mr-1" /> Feature löschen
                </Button>
                <p className="text-xs text-muted-foreground">
                  Nutze Löschen nur, wenn das Feature wirklich komplett aus dem Graphen entfernt werden soll.
                </p>
              </>
            ) : (
              <p className="text-xs text-muted-foreground">
                Speichern legt das Feature im Wizard-State an.
              </p>
            )}
          </div>

          <div className="flex flex-col gap-2 sm:items-end">
            <p className="text-xs text-muted-foreground">
              {saveError ?? (isDirty ? "Es gibt ungespeicherte Änderungen." : "Keine ungespeicherten Änderungen.")}
            </p>
            <div className="flex gap-2">
              <Button type="button" variant="outline" onClick={handleClose} disabled={isSaving}>Abbrechen</Button>
              <Button type="button" onClick={handleSave} disabled={isSaving}>
                {isSaving ? "Speichere..." : "Speichern"}
              </Button>
            </div>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

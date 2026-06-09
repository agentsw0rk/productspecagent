# Feature 53: Cockpit Feature-Daten aus S3

## Problem

Die Cockpit-Seite existiert bereits als Feature Workbench, nutzt fuer Feature-Liste und Feature-Graph aber noch lokale Beispiel-Daten. Dadurch wirkt das Cockpit nach dem Wizard wie ein Prototyp, obwohl die echten Wizard-Features bereits im Projekt-State persistiert werden.

Die fuer das Cockpit relevanten Feature-Daten liegen im S3-basierten Project Storage unter `projects/{id}/wizard.json`. Das Frontend soll diese Daten nicht direkt aus S3 lesen, sondern ueber die bestehende Backend-API laden.

## Ziel

Das Cockpit befuellt im ersten produktiven Schritt:

- den Feature-Graph im Hauptbereich
- die Feature-Auflistung in der linken Spalte

mit den echten Features aus dem Wizard-FEATURES-Step des jeweiligen Projekts. Status-Informationen wie `active`, `done` oder `in arbeit` werden noch nicht produktiv berechnet und stehen fuer alle Features zunaechst als Dummy auf `unknown`.

## Bestehender Zustand

- `frontend/src/app/projects/[id]/cockpit/page.tsx` prueft bereits, ob das Cockpit fuer das Projekt freigegeben ist.
- `frontend/src/components/cockpit/ProjectCockpitPrototype.tsx` rendert die Workbench mit `INITIAL_FEATURES`.
- `GET /api/v1/projects/{projectId}/wizard` liefert `WizardData`.
- `WizardData.steps.FEATURES.fields.features` enthaelt die Wizard-Features.
- `WizardData.steps.FEATURES.fields.edges` enthaelt die Feature-Abhaengigkeiten.
- Der Backend-Storage liest diese Daten nach Feature 31 aus dem S3-kompatiblen Object Store.

## Architektur

### Frontend

Die Cockpit-Page oder die Cockpit-Komponente laedt nach erfolgreicher Readiness-Pruefung zusaetzlich `getWizardData(projectId)`.

Aus den Wizard-Daten wird ein kleines Cockpit-ViewModel gebaut:

- `id` aus `WizardFeature.id`
- `title` aus `WizardFeature.title`
- `summary` aus `WizardFeature.description`
- `scopes` aus `WizardFeature.scopes`
- `graphPosition` aus `WizardFeature.position`
- `dependsOn` aus den Wizard-Edges
- `status` konstant `unknown`
- Review-, Test-, Design- und Handoff-Werte als neutrale Platzhalter

`ProjectCockpitPrototype` soll dabei nicht weiter als reiner Mock-Daten-Container wachsen. Wenn die Implementierung dadurch klarer wird, wird die Datenabbildung in eine kleine Hilfsfunktion ausgelagert, z. B. `toCockpitFeatures(wizardData)`.

### Backend / S3

Es wird keine neue S3-Direktanbindung im Frontend eingefuehrt.

Die produktive Datenkette ist:

```
Cockpit UI
  -> GET /api/v1/projects/{projectId}/wizard
  -> WizardController
  -> WizardService
  -> ProjectStorage
  -> S3 Object Store: projects/{id}/wizard.json
```

Eine neue Backend-Route ist nur dann noetig, wenn die bestehende Wizard-API im Code nicht stabil genug fuer die Cockpit-Ansicht typisiert werden kann. Bevorzugt wird die Wiederverwendung der vorhandenen API.

## Datenmodell

Der Cockpit-Status wird erweitert oder ersetzt durch einen produktionsnahen Dummy-Status:

```ts
type CockpitFeatureStatus = "unknown";
```

Falls die bestehende UI die alten Mock-Statuswerte noch fuer Styling benoetigt, darf der Typ temporaer beide Welten abbilden:

```ts
type CockpitFeatureStatus = "UNKNOWN" | "DONE" | "IN_PROGRESS" | "BLOCKED" | "PLANNED";
```

In diesem Feature muessen alle aus S3/Wizard-State geladenen Features sichtbar mit `unknown` dargestellt werden. Eine echte Statusberechnung ist ausdruecklich nicht Teil dieses Features.

## UX-Verhalten

- Beim Oeffnen des Cockpits erscheint ein Loading-Zustand, solange Projekt- und Wizard-Daten geladen werden.
- Wenn Wizard-Features vorhanden sind, ersetzen sie die bisherigen Beispiel-Features in linker Liste und Graph.
- Der aktive Eintrag wird initial auf das erste geladene Feature gesetzt.
- Doppelklick auf einen Graph-Node waehlt weiterhin das Feature in der linken Liste aus.
- Wenn keine Wizard-Features vorhanden sind, zeigt das Cockpit einen leeren Zustand statt Beispiel-Daten.
- Status-Badges zeigen fuer alle geladenen Features `Unknown`.

## Kontext7-Erkenntnisse

- Next.js App Router unterstuetzt die aktuelle Route weiterhin als Client Component mit `params: Promise<{ id: string }>` und `use(params)`.
- Client-seitiges Laden bestehender REST-Daten kann mit `useEffect` und lokalem Loading-/Error-State erfolgen.
- React empfiehlt bei asynchronem Laden im Effect eine Cleanup-Guard-Variable, damit spaete Antworten keine veralteten State-Updates schreiben.

## Akzeptanzkriterien

1. Das Cockpit laedt nach der bestehenden Readiness-Pruefung die Wizard-Daten des Projekts.
2. Die linke Feature-Liste zeigt die Features aus `steps.FEATURES.fields.features`.
3. Der Feature-Graph zeigt dieselben Features und die vorhandenen Wizard-Edges.
4. Die bisherigen `INITIAL_FEATURES` werden fuer Projekte mit echten Wizard-Features nicht mehr angezeigt.
5. Jedes geladene Feature zeigt den Status `unknown`.
6. Der initial aktive Eintrag ist das erste geladene Feature.
7. Doppelklick auf einen Graph-Node waehlt weiterhin das passende Feature aus.
8. Ein Projekt ohne Wizard-Features zeigt einen leeren Zustand statt Mock-Daten.
9. Lade- und Fehlerzustand fuer Wizard-Daten sind im Cockpit sichtbar.
10. Die Implementierung baut erfolgreich und verletzt keine gezielten ESLint-Regeln.

## Abhaengigkeiten

- Feature 22: Intelligenter FEATURES-Step mit DAG
- Feature 31: Project Storage auf S3
- Feature 50: Project Cockpit Feature Workbench
- Feature 52: Cockpit Launch Gate nach Wizard-Abschluss

## Nicht-Ziele

- Keine echte Statusberechnung fuer `active`, `done`, `in arbeit` oder Blocker.
- Keine Living-Sync-Aggregation.
- Keine Done-Markdown-Auswertung.
- Keine neue S3-Direktkommunikation aus dem Browser.
- Keine Persistenz von Cockpit-eigenen Interaktionen.
- Kein Redesign der Cockpit-Seite.

## Betroffene Dateien

- `frontend/src/app/projects/[id]/cockpit/page.tsx`
- `frontend/src/components/cockpit/ProjectCockpitPrototype.tsx`
- optional `frontend/src/lib/api.ts`, falls Typen fuer `steps.FEATURES.fields.edges` geschaerft werden
- optional neue kleine Mapping-Datei unter `frontend/src/lib/`, falls die Cockpit-Komponente sonst zu viel Datenumbau enthaelt

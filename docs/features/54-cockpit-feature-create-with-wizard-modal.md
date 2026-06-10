# Feature 54: Cockpit Feature-Erfassung mit Wizard-Modal

## Problem

Das Cockpit kann seit Feature 53 echte Wizard-Features aus dem S3-basierten Project Storage anzeigen. Beim Erstellen eines neuen Features nutzt die Cockpit-Seite aber weiterhin nur ein kleines Inline-Feld fuer den Titel:

- Es werden keine Beschreibung, Scopes, scope-spezifischen Details oder Akzeptanzkriterien erfasst.
- Die Eingabe folgt nicht den Projekt-Kategorie-Regeln aus dem Wizard.
- Neue Features werden nur im lokalen Cockpit-State angelegt und nicht als `WizardFeature` in `wizard.json` persistiert.

Dadurch entstehen im Cockpit Features, die fachlich deutlich weniger Daten haben als Features, die im Wizard-FEATURES-Step angelegt wurden.

## Ziel

Beim Klick auf `Feature erfassen` im Cockpit soll dieselbe Feature-Erfassung moeglich sein wie im Wizard:

- Titel
- Beschreibung
- Scopes passend zur Projekt-Kategorie
- scope-spezifische Detailfelder
- Akzeptanzkriterien
- optional AI-Vorschlag fuer Akzeptanzkriterien, wenn die bestehende Wizard-API dies fuer das Feature unterstuetzt

Das neue Feature wird als `WizardFeature` in `WizardData.steps.FEATURES.fields.features` gespeichert und danach in der Cockpit-Liste und im Cockpit-Graph angezeigt.

## Analyse des bestehenden Wizard-Verhaltens

### Einstieg

- `frontend/src/components/wizard/steps/FeaturesForm.tsx` rendert `FeaturesGraphEditor`.
- `FeaturesGraphEditor` liest `features` und `edges` aus dem Wizard-Store.
- Der Button `+ Feature` erzeugt aktuell ein neues `WizardFeature` mit:
  - `title: "Neues Feature"`
  - leerer Beschreibung
  - `scopes: allowedScopes.slice(0, 1)`
  - leerem `scopeFields`
  - leerer `acceptanceCriteria`
  - initialer Position
- Danach wird die neue Feature-ID selektiert und `FeatureEditDialog` geoeffnet.

### Projekt-Setup und erlaubte Scopes

Die passenden Eingabefelder haengen an der Projekt-Kategorie:

- Kategorie wird aus `WizardData.steps.IDEA.fields.category` gelesen.
- `getAllowedScopesFromCatalog(catalog, category)` liefert die erlaubten Scopes.
- SaaS, Mobile App und Desktop App erlauben `FRONTEND` und `BACKEND`.
- API und CLI Tool erlauben nur `BACKEND`.
- Library erlaubt keine Frontend/Backend-Scopes und nutzt Core-Felder.

Die Scope-Detailfelder kommen aus `frontend/src/lib/step-field-labels.ts`:

- `FRONTEND`: `uiComponents`, `screens`, `userInteractions`
- `BACKEND`: `apiEndpoints`, `dataModel`, `sideEffects`
- `CORE`: `publicApi`, `typesExposed`, `examples`

### Modal

`frontend/src/components/wizard/steps/features/FeatureEditDialog.tsx` ist die zentrale Erfassungsmaske:

- nutzt shadcn `Dialog`, `Input`, `Textarea`, `Label` und bestehende `Button`
- erzeugt beim Oeffnen einen lokalen Draft aus dem aktuellen `WizardFeature`
- trackt Dirty-State mit `equalDraft`
- speichert erst bei `Speichern`
- `Abbrechen`, Esc und Overlay-Klick fragen bei Dirty per `window.confirm`
- `Loeschen` ruft `onDelete`
- Scope-Toggles wirken sofort auf den lokalen Draft
- ausgeblendete `scopeFields` bleiben im Draft erhalten
- Akzeptanzkriterien koennen manuell erfasst, sortiert und geloescht werden
- leere Akzeptanzkriterien werden beim Speichern entfernt

Der Dialog ist aktuell edit-orientiert und erwartet ein existierendes `WizardFeature`. Fuer das Cockpit sollte er entweder um einen Create-Modus erweitert oder mit einem noch nicht persistierten Draft-Feature geoeffnet werden.

### Persistenz

Im Wizard persistiert `wizard-store` Aenderungen ueber:

- `addFeature`
- `updateFeature`
- `removeFeature`
- `addEdge` / `removeEdge`
- `saveStep(projectId, "FEATURES")`

Im Cockpit ist der Wizard-Store nicht aktiv. Feature 53 laedt stattdessen direkt:

- `getProject(projectId)`
- `getWizardData(projectId)`
- `toCockpitFeatures(wizardData)`

Fuer dieses Feature muss das Cockpit beim Speichern eines neuen Features die vorhandene API `saveWizardStep(projectId, "FEATURES", stepData)` nutzen und danach den lokalen Cockpit-State aus den aktualisierten Wizard-Daten neu ableiten.

## Architektur

### Frontend

Die Cockpit-Seite bleibt eine eigene Workbench und uebernimmt nicht den gesamten Wizard-Store.

Empfohlene Struktur:

- `frontend/src/app/projects/[id]/cockpit/page.tsx`
  - laedt weiterhin Projekt- und Wizard-Daten
  - uebergibt zusaetzlich die fuer Erstellung noetigen Wizard-Daten oder abgeleiteten Werte an das Cockpit
- `frontend/src/components/cockpit/ProjectCockpitPrototype.tsx`
  - ersetzt das aktuelle Inline-Title-Formular durch einen Dialog-Flow
  - haelt lokale Cockpit-Features fuer Anzeige und Auswahl
  - ruft bei `Feature erfassen` einen Create-Dialog auf
- `frontend/src/components/wizard/steps/features/FeatureEditDialog.tsx`
  - wird wiederverwendet
  - wird bei Bedarf um Create-Modus oder neutralere Texte erweitert
- `frontend/src/lib/cockpit-feature-data.ts`
  - erweitert das Mapping so genuegend Wizard-Daten fuer Save/Reload erhalten bleiben
  - optional Helper fuer `createDefaultWizardFeature(allowedScopes, position)`

### Datenfluss

```text
Cockpit Page
  -> getProject(projectId)
  -> getWizardData(projectId)
  -> getWizardOptions() oder WizardOptionsStore
  -> getAllowedScopesFromCatalog(catalog, category)
  -> ProjectCockpitPrototype
```

Beim Erstellen:

```text
User klickt Feature erfassen
  -> Cockpit erzeugt Draft-WizardFeature mit Default-Scopes
  -> FeatureEditDialog oeffnet mit denselben Feldern wie im Wizard
  -> Speichern erzeugt bereinigtes WizardFeature
  -> saveWizardStep(projectId, "FEATURES", { fields: { features: nextFeatures, edges }, completedAt })
  -> Cockpit mapped aktualisierte Wizard-Daten erneut via toCockpitFeatures
  -> neues Feature ist in Liste und Graph sichtbar
```

### Datenmodell

Das Cockpit darf fuer Anzeige weiterhin ein `CockpitFeature`-ViewModel nutzen. Fuer Erstellung und Persistenz muss aber der originale Wizard-State erhalten bleiben:

- `WizardFeature.id`
- `title`
- `description`
- `scopes`
- `scopeFields`
- `acceptanceCriteria`
- `position`

Die bestehenden `edges` muessen beim Speichern unveraendert erhalten bleiben. Ein neu angelegtes Feature startet ohne Abhaengigkeiten.

## Kontext7- und shadcn-Erkenntnisse

- React-Formularzustand soll lokal und kontrolliert bleiben: Eingabewerte liegen im Component-State und werden bei `Speichern` an den Parent uebergeben.
- Objektfoermiger Form-State soll ersetzt statt mutiert werden, also per Spread/neu erzeugtem Objekt.
- Client-seitiges Laden in Next.js Client Components nutzt lokale Loading-/Error-States oder bestehende Fetch-Hooks; fuer dieses Projekt reicht das vorhandene API-Pattern.
- shadcn `Dialog` ist ein kontrollierbarer Dialog um Radix Dialog Primitive mit `open` und `onOpenChange`; das passt zum bestehenden `FeatureEditDialog`.

## UX-Verhalten

- `Feature erfassen` oeffnet ein Modal statt des bisherigen Inline-Felds.
- Das Modal zeigt dieselben Eingaben wie der Wizard-Feature-Dialog.
- Der Dialog-Titel darf im Cockpit `Feature erfassen` lauten; die Felder bleiben identisch.
- `Speichern` persistiert das Feature in den Wizard-FEATURES-Step und schliesst das Modal.
- `Abbrechen`, Esc und Overlay-Klick verwerfen bei bestaetigtem Dirty-Confirm den Draft und erzeugen kein Feature.
- Nach erfolgreichem Speichern wird das neue Feature aktiv ausgewaehlt.
- Wenn das Speichern fehlschlaegt, bleibt der Dialog offen und zeigt einen Fehlerzustand.
- Das neue Feature erscheint im Cockpit-Graph ohne neue Abhaengigkeiten.

## Akzeptanzkriterien

1. Der Cockpit-Button `Feature erfassen` oeffnet ein Modal statt eines Inline-Titelfelds.
2. Das Modal bietet Titel, Beschreibung, Scopes, scope-spezifische Felder und Akzeptanzkriterien wie der Wizard.
3. Die erlaubten Scopes richten sich nach der Projekt-Kategorie aus dem Wizard-IDEA-Step.
4. Library-Projekte zeigen Core-Felder statt Frontend/Backend-Scope-Toggles.
5. API- und CLI-Projekte erzeugen neue Features mit Backend-Scope ohne sichtbaren Scope-Multiselect.
6. `Speichern` legt ein neues `WizardFeature` in `steps.FEATURES.fields.features` an.
7. Bestehende `steps.FEATURES.fields.edges` bleiben beim Speichern unveraendert.
8. Das neue Feature wird nach dem Speichern in linker Cockpit-Liste und Feature-Graph angezeigt.
9. Das neue Feature wird nach dem Speichern aktiv ausgewaehlt.
10. `Abbrechen` erzeugt kein Feature.
11. Bei Dirty-State fragt der Dialog vor dem Verwerfen nach.
12. Das Feature baut erfolgreich und verletzt keine gezielten ESLint-Regeln.

## Abhaengigkeiten

- Feature 22: Intelligenter FEATURES-Step mit DAG
- Feature 36: Features-Edit-Modal
- Feature 44: Akzeptanzkriterien im Feature-Edit-Modal
- Feature 50: Project Cockpit Feature Workbench
- Feature 53: Cockpit Feature-Daten aus S3

## Nicht-Ziele

- Keine neue Feature-Proposal-Funktion im Cockpit.
- Keine Bearbeitung von Graph-Abhaengigkeiten im Cockpit.
- Keine Statusberechnung fuer `done`, `active` oder `in arbeit`.
- Keine neue Backend-Route.
- Keine Migration des Cockpits auf den kompletten Wizard-Store.
- Kein Redesign des Cockpit-Layouts.

## Betroffene Dateien

- `frontend/src/app/projects/[id]/cockpit/page.tsx`
- `frontend/src/components/cockpit/ProjectCockpitPrototype.tsx`
- `frontend/src/lib/cockpit-feature-data.ts`
- `frontend/src/components/wizard/steps/features/FeatureEditDialog.tsx`
- optional `frontend/src/lib/api.ts`, falls ein typisierter Helper fuer `saveWizardStep`-Payloads sinnvoll ist
- Tests unter `frontend/e2e/`

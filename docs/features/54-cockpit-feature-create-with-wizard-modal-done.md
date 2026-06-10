# Feature 54: Cockpit Feature-Erfassung mit Wizard-Modal - Done

## Umgesetzt

- `Feature erfassen` im Cockpit oeffnet jetzt den bestehenden Wizard-Dialog statt des bisherigen Inline-Titelfelds.
- Der Dialog laeuft im Cockpit im Create-Modus mit Titel, Beschreibung, Scopes, scope-spezifischen Feldern und Akzeptanzkriterien.
- Die erlaubten Scopes werden aus der Wizard-Kategorie ueber `getAllowedScopesFromCatalog` abgeleitet.
- Neue Features werden als `WizardFeature` im `FEATURES`-Step ueber `saveWizardStep(projectId, "FEATURES", stepData)` gespeichert.
- Bestehende Feature-Edges bleiben beim Speichern unveraendert erhalten.
- Nach erfolgreichem Speichern werden Cockpit-Liste und Feature-Graph aus den aktualisierten Wizard-Daten neu abgeleitet.
- Das neu gespeicherte Feature wird im Cockpit aktiv ausgewaehlt.

## Validierung

- `cd frontend && npx playwright test e2e/cockpit-feature-data.spec.ts`
- `cd frontend && npm run lint`
- `cd frontend && npm run build`
- `git diff --check`

## Hinweise

- Statuswerte wie `active`, `done` und `in arbeit` bleiben weiterhin Dummy-Daten und werden als `UNKNOWN` gemappt.
- AI-Vorschlaege fuer Akzeptanzkriterien bleiben im Cockpit-Create-Modus deaktiviert, weil die bestehende API ein bereits persistiertes Feature erwartet.

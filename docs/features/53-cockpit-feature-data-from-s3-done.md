# Feature 53: Cockpit Feature-Daten aus S3 - Done

## Zusammenfassung

Das Cockpit nutzt fuer Feature-Liste und Feature-Graph nun die echten Wizard-FEATURES-Daten des Projekts statt lokaler Beispiel-Daten.

- Die Cockpit-Route laedt nach der bestehenden Readiness-Pruefung `GET /api/v1/projects/{projectId}/wizard`.
- Ein neues Mapping-Modul wandelt `WizardData.steps.FEATURES.fields.features` und `edges` in Cockpit-Features um.
- Alle geladenen Features erhalten vorerst den Status `UNKNOWN`.
- Der erste geladene Eintrag wird initial aktiv.
- Projekte ohne Wizard-Features zeigen einen leeren Zustand statt Mock-Daten.
- Wizard-Ladefehler werden im Cockpit sichtbar angezeigt.

## Abweichungen

- Die Daten werden wie geplant ueber die bestehende Wizard-API geladen; es wurde keine neue Backend-Route eingefuehrt.
- Der Status-Typ bleibt temporaer kompatibel mit den alten Cockpit-Statuswerten, damit vorhandene UI-Logik und lokale Cockpit-Aktionen nicht unnoetig umgebaut werden. Produktiv geladene Features verwenden aber ausschliesslich `UNKNOWN`.
- Der gezielte Test prueft die reine Wizard-zu-Cockpit-Abbildung. Ein vollstaendiger Cockpit-E2E-Test wurde nicht ergaenzt, weil die vorhandene E2E-Fixture Wizard-Daten speichert, aber den produktiven Cockpit-Launch-State nicht ohne Agentenabschluss auf 100% setzt.

## Offene Punkte

- Produktive Statusberechnung fuer `active`, `done`, `in arbeit` und Blocker bleibt offen.
- Test-/Evidence-/Handoff-Daten sind weiterhin neutrale Platzhalter.
- Eine spaetere E2E-Fixture fuer cockpit-ready Projekte wuerde einen UI-Test gegen `/projects/{id}/cockpit` erlauben.

## Validierung

- `cd frontend && npx playwright test e2e/cockpit-feature-data.spec.ts`
- `cd frontend && npm run lint`
- `cd frontend && npm run build`

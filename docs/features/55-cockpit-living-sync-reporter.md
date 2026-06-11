# Feature 55: Cockpit Living-Sync-Reporter-Anbindung

## Zusammenfassung

Das Cockpit soll die Ergebnisse des bestehenden `living-sync-reporter` in der vorhandenen Feature-Workbench anzeigen. Feature 45 und 51 liefern bereits Living-Sync-Events, Summary-Daten und Feature-Completion-Snapshots. Feature 53/54 zeigen echte Wizard-Features im Cockpit, nutzen fuer Status, Tests, Evidence und Review aber noch Platzhalter.

## Ziel

Die Cockpit-Route laedt zusaetzlich zur Wizard-Feature-Datenquelle die Living-Sync-Summary des Projekts und merged beide Datenquellen in das vorhandene `CockpitFeature`-ViewModel.

## Architektur

- Keine neue Backend-API.
- `GET /api/v1/projects/{projectId}/living-sync` bleibt die Quelle fuer Reporter-Daten.
- `frontend/src/lib/cockpit-feature-data.ts` bleibt der zentrale Mapper fuer Cockpit-ViewModels.
- `frontend/src/app/projects/[id]/cockpit/page.tsx` laedt Wizard-Daten, Wizard-Optionen und Living-Sync-Summary parallel.
- `frontend/src/components/cockpit/ProjectCockpitPrototype.tsx` behaelt Layout und Struktur, zeigt aber echte Reporter-Zustaende statt generischer "nicht angebunden"-Texte.

## Mapping

- Wizard-Daten liefern Titel, Beschreibung, Scopes, Graph-Positionen und Abhaengigkeiten.
- `featureCompletions` liefern Status, Summary, Tests, offene Punkte, Abweichungen, Warnungen und technische Schulden.
- Feature-Progress-Events liefern Status und Summary, wenn kein Completion-Snapshot vorhanden ist.
- `TEST_RUN`, `CODE_CHANGES`, `SYNC_NOTE` und Token-Events liefern Evidence, Testzustand und Review-Hinweise.
- Handoff-Reife wird aus Status, Testzustand, Evidence und offenen Review-Punkten abgeleitet.

## Akzeptanzkriterien

- [ ] Cockpit laedt `GET /api/v1/projects/{id}/living-sync`.
- [ ] Wizard-Features werden mit Living-Sync-Summary gemerged.
- [ ] Completion-Snapshots ueberschreiben Cockpit-Platzhalter fuer Status, Summary, Tests, offene Punkte und Warnungen.
- [ ] Feature-Progress-Events liefern Status und Summary, wenn kein Snapshot existiert.
- [ ] Test-/Evidence-Bereich zeigt letzte relevante Reporter-Events statt generischer Platzhalter.
- [ ] Bestehendes Cockpit-Layout bleibt erhalten.
- [ ] Gezielt erweiterte Cockpit-Mapping-Tests laufen gruen.

## Abhaengigkeiten

- Feature 45: Living-Sync via MCP
- Feature 51: Feature-Done-Markdown Import via MCP
- Feature 53: Cockpit Feature-Daten aus S3
- Feature 54: Cockpit Feature-Erfassung mit Wizard-Modal

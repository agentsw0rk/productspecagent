# Feature 58 — Wizard Autosave Race Guard

**Phase:** Wizard UX  
**Abhängig von:** Feature 11  
**Aufwand:** S

## Problem

Wizard-Eingaben werden local-first in den Frontend-State geschrieben und nach kurzer Verzögerung per `PUT /api/v1/projects/{projectId}/wizard/{step}` gespeichert. Wenn ein Nutzer weiter tippt, während eine ältere Autosave-Anfrage noch läuft, kann die ältere Serverantwort neuere lokale Eingaben überschreiben. Das wirkt so, als würden beim Tippen Zeichen verloren gehen.

## Ziel

Wizard-Eingaben bleiben beim Tippen immer sofort lokal sichtbar. Autosave läuft weiter debounced im Hintergrund, aber veraltete Serverantworten dürfen den lokalen Wizard-State nicht mehr ersetzen.

## Architektur

- Der Wizard-Store führt eine monotone lokale Edit-Revision.
- Jede lokale Feldänderung erhöht diese Revision.
- Jede Save-Anfrage merkt sich die Revision beim Start.
- Eine Save-Antwort darf `data` nur ersetzen, wenn ihre Start-Revision noch der aktuellen lokalen Revision entspricht.
- Antworten alter Requests beenden nur ihren Saving-Status und lassen neuere lokale Eingaben unverändert.
- Wenn während eines laufenden Saves weitergetippt wird, plant das bestehende Debounce-Verhalten automatisch einen weiteren Save mit dem neuesten Step-Snapshot.

## Betroffene Dateien

- `frontend/src/lib/stores/wizard-store.ts`
- `frontend/e2e/wizard-autosave.spec.ts`
- `frontend/playwright.config.ts`

## Akzeptanzkriterien

1. Eingaben werden weiterhin sofort im Feld angezeigt.
2. Autosave verwendet weiterhin `PUT /api/v1/projects/{projectId}/wizard/{step}` im Hintergrund.
3. Veraltete Autosave-Antworten überschreiben keine neueren lokalen Eingaben.
4. Ein neuer Save wird weiterhin ausgelöst, wenn während eines laufenden Saves weitergetippt wird.
5. Manuelle Step-Saves übernehmen Serverdaten ebenfalls nur, wenn seit Request-Start keine neuere lokale Revision entstanden ist.
6. Ein E2E-Test reproduziert eine alte Serverantwort und hält den neueren lokalen Input stabil.

## Out of Scope

- Backend-Versionierung oder Optimistic-Locking
- Konfliktauflösung zwischen mehreren Browser-Tabs
- Persistenter Offline-Modus
- Änderung des Wizard-API-Vertrags

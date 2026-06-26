# Feature 58 — Wizard Autosave Race Guard Done

**Spec:** [`58-wizard-autosave-race-guard.md`](58-wizard-autosave-race-guard.md)

## Zusammenfassung

Der Wizard-Store schützt debounced Autosave-Antworten jetzt mit einer lokalen Edit-Revision. Jede Eingabe erhöht die Revision; Save-Anfragen speichern die Revision beim Start und dürfen `data` nur übernehmen, wenn seitdem keine neuere Eingabe passiert ist. Dadurch bleiben neuere lokale Texte stabil, selbst wenn eine ältere `PUT /wizard/{step}`-Antwort später zurückkommt.

## Abweichungen

- Es wurde keine Backend-Versionierung eingeführt. Die Lösung bleibt bewusst frontend-seitig und ändert den bestehenden API-Vertrag nicht.
- Die Playwright-Konfiguration akzeptiert optional `PLAYWRIGHT_BASE_URL`, damit fokussierte E2E-Läufe gegen einen laufenden Devserver auf einem alternativen Port möglich bleiben. Der Default bleibt `http://localhost:3000`.

## Verifikation

- `cd frontend && npm run test:e2e -- e2e/wizard-autosave.spec.ts --project=chromium`
- `cd frontend && npm run lint`
- `cd frontend && npm run build`

## Offene Punkte

- Mehrere gleichzeitig geöffnete Browser-Tabs können sich weiterhin gegenseitig überschreiben; dafür wäre Backend-Revisionierung oder Optimistic-Locking nötig.
- Der globale Autosave-Timer bleibt ein bestehendes Store-Muster. Diese Änderung behebt den Race beim Anwenden von Save-Antworten, strukturiert den Autosave-Mechanismus aber nicht grundlegend um.

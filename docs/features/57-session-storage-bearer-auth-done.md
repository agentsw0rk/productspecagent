# Feature 57 — Session-Storage Bearer Auth Done

**Spec:** [`57-session-storage-bearer-auth.md`](57-session-storage-bearer-auth.md)  
**Design:** [`docs/superpowers/specs/2026-06-15-session-storage-bearer-auth-design.md`](../superpowers/specs/2026-06-15-session-storage-bearer-auth-design.md)  
**Plan:** [`docs/superpowers/plans/2026-06-15-session-storage-bearer-auth.md`](../superpowers/plans/2026-06-15-session-storage-bearer-auth.md)

## Zusammenfassung

Die Authentifizierung wurde von `httpOnly` Cookie-Transport auf `Authorization: Bearer <token>` umgestellt. Login und Registrierung liefern den JWT im JSON zurück. Das Frontend speichert den Token in `sessionStorage`, hängt ihn an API-Requests an und schützt Seiten clientseitig nach Hydration. Das Backend authentifiziert stateless über den Authorization-Header.

## Abweichungen

- Es gibt keinen Next.js Proxy/Middleware-Auth-Gate mehr, weil `sessionStorage` nur im Browser verfügbar ist.
- Logout invalidiert serverseitig keinen Token; der Token wird clientseitig gelöscht.
- Die Design-Preview wird authentifiziert als HTML geladen und als Blob-URL im iframe gerendert, weil iframe-Navigation keine Authorization-Header setzen kann.

## Verifikation

- `cd backend && ./gradlew test --tests "*AuthControllerTest" --tests "*JwtAuthenticationFilterTest" --tests "*SecurityIntegrationTest"`
- `cd frontend && npm run lint`
- `cd frontend && npm run test:e2e -- auth-api.spec.ts`

## Offene Punkte

- Token-Lifetime ist weiterhin die bestehende JWT-Laufzeit.
- Ein späteres Hardening-Feature kann eine kürzere Laufzeit oder zusätzliche XSS-Schutzmaßnahmen definieren.
- Es gibt keine dedizierten Component-Tests für Blob-URL-Cleanup und Race-Fälle der Design-Preview.

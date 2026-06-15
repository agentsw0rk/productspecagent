# Feature 57 — Session-Storage Bearer Auth

**Phase:** Auth/Security  
**Abhängig von:** Feature 42  
**Aufwand:** M  
**Design-Spec:** [`docs/superpowers/specs/2026-06-15-session-storage-bearer-auth-design.md`](../superpowers/specs/2026-06-15-session-storage-bearer-auth-design.md)

## Problem

Feature 42 speichert den JWT in einem `httpOnly` Cookie. Im Deployment verursacht der Cookie/CORS/Domain-Aufbau Probleme. Die Authentifizierung soll deshalb ohne Cookie funktionieren.

## Ziel

JWT-Transport über `Authorization: Bearer <token>` und Speicherung des Tokens im Browser-`sessionStorage`. Login gilt pro Browser-Tab/Fenster. Geschützte API-Endpunkte bleiben serverseitig durch Spring Security geschützt; geschützte Seiten werden clientseitig nach Hydration gegated.

## Architektur

- Backend gibt bei Login/Register `{ token, userId, email, admin }` zurück.
- Backend liest JWTs nur aus dem `Authorization`-Header.
- Frontend speichert den Token in `sessionStorage`.
- Frontend hängt den Token an alle API-Requests an.
- Der frühere Next.js `proxy.ts`-Gate entfällt; Seiten-Gating passiert im Client nach Hydration.

## Akzeptanzkriterien

1. Login/Register liefern einen Token im JSON und setzen kein Auth-Cookie.
2. API-Requests senden `Authorization: Bearer <token>`.
3. Geschützte Endpoints liefern ohne Token 401.
4. Reload mit Token stellt die Session via `/api/v1/auth/me` wieder her.
5. Reload ohne Token leitet geschützte Seiten zu `/login`.
6. Logout löscht den Token aus `sessionStorage`.
7. Admin-Rechte funktionieren weiter über konfigurierte Admin-Emails.
8. Cookie-spezifische Tests sind durch Bearer-Token-Tests ersetzt.

## Out of Scope

- Refresh Tokens
- Server-Side Token Invalidation
- OAuth/SSO
- `localStorage`
- Rollen- oder Mitglieder-Modell

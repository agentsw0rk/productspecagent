# Architecture: Authentifizierung

> **Stand: 2026-06-15** — überarbeitet im Rahmen von [Feature 57 — Session-Storage Bearer Auth](../features/57-session-storage-bearer-auth.md). Feature 42 führte das Login-Gate ein; Feature 57 ersetzt den Cookie-Transport durch Bearer Tokens.

## Überblick

Login-Gate vor jeder Aktion via Spring Security + selbst-signiertem JWT. Das Backend gibt den JWT bei Login/Register im JSON zurück, das Frontend speichert ihn im Browser-`sessionStorage` und sendet ihn bei API-Requests als `Authorization: Bearer <token>`.

Eingeloggte User teilen einen gemeinsamen Workspace — keine Mitglieder, keine Rollen, keine Per-User-Projekt-Ownership. Admin-Rechte werden weiterhin aus konfigurierten Admin-Emails abgeleitet.

## Flow

1. User registriert sich via `POST /api/v1/auth/register` (`{email, password}`).
2. User loggt sich ein via `POST /api/v1/auth/login` (`{email, password}`).
3. Backend validiert BCrypt-Hash, signiert JWT (HMAC-SHA256, ENV-Secret) und antwortet mit `{ token, userId, email, admin }`.
4. Frontend speichert `token` in `sessionStorage`.
5. Frontend sendet bei API-Requests `Authorization: Bearer <token>`.
6. Backend (`JwtAuthenticationFilter`) liest den `Authorization`-Header, validiert JWT und setzt den `SecurityContext`.
7. Reload mit Token stellt die Session via `GET /api/v1/auth/me` wieder her.
8. Logout via `POST /api/v1/auth/logout` ist backendseitig stateless und antwortet `204`; das Frontend löscht den Token aus `sessionStorage`.

## Token-Strategie

- JWT im JSON-Response bei Login/Register, 7 Tage gültig, fix (kein Sliding, kein Refresh-Token).
- Speicherung pro Browser-Tab/Fenster in `sessionStorage`; ein neuer Tab startet ohne geteilten Login-Zustand.
- Bei Ablauf oder fehlendem Token loggt sich der User neu ein.
- Bei Logout-Wunsch von einem fremden Gerät: keine Server-Side-Invalidation in dieser Iteration (akzeptiertes Risiko bei interner Nutzung).

## Persistenz

- **User**: JSON-Files unter `data/users/{user-id}.json`
- **Email-Index**: Pointer-Files unter `data/users/_index/email-{lowercase-email}.json` mit `{ userId }` — O(1) Email-Lookup ohne Map-Datei
- **Passwörter**: BCrypt mit Strength 10
- **Keine** `members.json` pro Projekt (kein Member-Modell in dieser Iteration)

## Endpoints

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/v1/auth/register` | `{email, password}` | 201 + `{token, userId, email, admin}` |
| POST | `/api/v1/auth/login` | `{email, password}` | 200 + `{token, userId, email, admin}` |
| POST | `/api/v1/auth/logout` | — | 204 |
| GET | `/api/v1/auth/me` | — | 200 `{userId, email, admin}` oder 401 |

Error-Bodies generisch (`INVALID_CREDENTIALS`, `EMAIL_ALREADY_EXISTS`, `VALIDATION_ERROR`) — kein Hinweis auf Email-Existenz (User-Enumeration).

## Spring Security Setup

- Stateless Sessions (`SessionCreationPolicy.STATELESS`)
- `SecurityFilterChain`:
  - `permitAll()` für `/api/health`
  - `permitAll()` für `/api/v1/auth/register`, `/api/v1/auth/login` und `/api/v1/auth/logout`
  - `permitAll()` für `GET /api/v1/config/features`
  - `permitAll()` für `GET /api/v1/wizard-options`
  - `permitAll()` für `GET /api/v1/projects/*/handoff/handoff.zip`
  - `permitAll()` für `POST /api/v1/projects/*/living-sync/**`
  - `permitAll()` für `POST /mcp`
  - Admin-only Gruppen bleiben role-gated (`/api/v1/admin/wizard-options/**`, `/api/v1/prompts/**`, `/api/v1/agent-models/**`, `/api/v1/asset-bundles/**`)
  - `authenticated()` für geschützte API-Endpunkte
  - `JwtAuthenticationFilter` vor `UsernamePasswordAuthenticationFilter`
  - `AuthenticationEntryPoint` setzt 401 ohne Body-Redirect (JSON-API)
- `JwtAuthenticationFilter` validiert ausschließlich Bearer Tokens aus dem `Authorization`-Header.
- `BCryptPasswordEncoder(strength = 10)`
- CORS erlaubt die konkrete Frontend-Origin; Authentifizierung hängt nicht von Browser-Credentials ab.

## Frontend-Auth (Next.js 16 App Router)

- Public Routes: `/`, `/login`, `/register`, `/start-screen`
- `auth-store` (Zustand) hält Userdaten und Token im Client-State und synchronisiert den Token mit `sessionStorage`.
- `lib/api.ts` hängt bei vorhandenem Token `Authorization: Bearer <token>` an API-Requests an.
- Globaler 401-Handler löscht Auth-Store und `sessionStorage` und navigiert zu `/login`.
- Es gibt keinen Next.js Proxy/Middleware-Auth-Gate mehr; geschützte Seiten werden clientseitig nach Hydration gegated, weil `sessionStorage` nur im Browser verfügbar ist.

## Konfiguration

```yaml
auth:
  admin-emails: "${AUTH_ADMIN_EMAILS:},arekczarnik@googlemail.com"
  jwt:
    secret: ${AUTH_JWT_SECRET:dev-secret-change-me-dev-secret-change-me-dev-secret}
    expiry-seconds: 604800
```

`AUTH_JWT_SECRET` sollte in deployten Umgebungen gesetzt werden und mindestens 32 Bytes lang sein. Der Repository-Default ist ein Entwicklungs-Fallback; Deployments müssen ihn durch ein echtes Secret ersetzen. Beispiel-Generierung: `openssl rand -base64 48`.

`AUTH_ADMIN_EMAILS` ist eine kommaseparierte Liste. `*` kann in lokaler/dev-Konfiguration verwendet werden, wenn bewusst alle User Admin-Rechte erhalten sollen.

## Security Notes

- CSRF-Risiko ist reduziert, weil der Browser den Token nicht automatisch als Cookie mitsendet.
- XSS-Auswirkung ist höher: Script-Ausführung im Frontend kann den Token aus `sessionStorage` lesen.
- `sessionStorage` ist bewusst gegenüber `localStorage` gewählt, damit Login-Zustand pro Browser-Tab/Fenster begrenzt bleibt.
- Es gibt keine Refresh Tokens, kein Sliding Expiration und keine Server-Side Token-Invalidation.
- Die frühere Cookie-Architektur war Deployment-seitig anfällig für CORS-/Domain-Konfiguration; der aktuelle Transport vermeidet diese Kopplung.

## Password-Regeln

NIST 800-63B-konform:
- Minimum 8 Zeichen
- Maximum 128 Zeichen
- Keine Komplexitätsregeln (Großbuchstaben/Zahlen/Sonderzeichen *nicht* erzwungen — gilt als kontraproduktiv)

## Bewusst nicht enthalten

- Rollen (`OWNER`, `CONTRIBUTOR`, `VIEWER`) und Mitgliedschaften
- Refresh-Tokens, Sliding Expiration, Server-Side Token-Invalidation
- Email-Verifikation, Password-Reset, Account-Löschung
- OAuth/SSO/SAML
- Rate-Limiting auf `/api/v1/auth/login`

Diese Punkte können als spätere Features ergänzt werden, sobald die Anwendungsfälle eintreten.

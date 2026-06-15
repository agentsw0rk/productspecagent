# Feature 42 — Login-Gate

**Phase:** Auth/Security
**Abhängig von:** Feature 0 (Project Setup, done)
**Aufwand:** M
**Design-Spec:** historisches Superpowers-Artefakt, nicht versioniert

> **Hinweis:** Feature 42 beschreibt die ursprüngliche Login-Gate-Implementierung mit Cookie-Transport. Der aktuelle Auth-Transport wurde in [Feature 57 — Session-Storage Bearer Auth](57-session-storage-bearer-auth.md) auf `Authorization: Bearer <token>` + `sessionStorage` umgestellt. Für aktuelle Architekturdetails siehe [Architecture: Authentifizierung](../architecture/auth.md).

## Problem

Die App hat heute keinen Zugangsschutz: `SecurityConfig.kt` setzt `permitAll()` auf `/api/v1/**`, das Frontend hat weder Login-Seiten noch Auth-Concept. Sobald die App über `localhost` hinaus erreichbar ist, sind alle Projekte, Spec-Files, Decisions, Tasks und Uploads ungeschützt.

## Ursprüngliches Ziel

Login-Gate mit Selbst-Registrierung vor jeder Aktion. Eingeloggte User teilen einen gemeinsamen Workspace (alle sehen alle Projekte). Keine Rollen, keine Email-Verifikation, kein Password-Reset — strikt YAGNI.

## Ursprüngliche Architektur

Siehe Design-Spec für das vollständige Bild. Kurzfassung:

- **Backend:** neue Pakete `auth/` (User, JwtService, AuthController, JwtAuthenticationFilter, UserStorage). `SecurityConfig` umgestellt auf `authenticated()` für alles ausser `/api/v1/auth/**` und `/api/health`. Stateless JWT in httpOnly-Cookie (HMAC-SHA256, 7 Tage fix, kein Refresh). BCrypt-Strength 10.
- **Frontend:** `proxy.ts` prüft optimistisch das Session-Cookie und redirected zu `/login`. Neue Routes `app/(auth)/login` + `app/(auth)/register` mit shadcn-Forms. `lib/api.ts` erweitert um `credentials: 'include'` und globalen 401-Handler. `auth-store` (Zustand) hält den User und blockt Flash-of-Login bei Reload via `GET /api/v1/auth/me` im Root-Layout.
- **Lib:** `io.jsonwebtoken:jjwt-api/jjwt-impl/jjwt-jackson` (selbst-signierte Tokens, knapper als `oauth2ResourceServer`).

## Datenmodell

```kotlin
@Serializable
data class User(
    val id: String,            // UUIDv4
    val email: String,         // lowercase, eindeutig
    val passwordHash: String,  // BCrypt, Strength 10
    val createdAt: String      // ISO-8601 UTC
)
```

Persistiert als `data/users/{user-id}.json`. Email-Index als Pointer-File `data/users/_index/email-{lowercase}.json` (`{ userId }`) — konsistent mit No-DB-Konzept, kein Locking.

## Ursprüngliche API

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/v1/auth/register` | `{email, password}` | 201 + `Set-Cookie` + `{userId, email}` |
| POST | `/api/v1/auth/login` | `{email, password}` | 200 + `Set-Cookie` + `{userId, email}` |
| POST | `/api/v1/auth/logout` | — | 204 + `Set-Cookie: session=; Max-Age=0` |
| GET | `/api/v1/auth/me` | — (Cookie) | 200 `{userId, email}` oder 401 |

Error-Bodies generisch (`{error, message}`), keine User-Enumeration.

## Ursprüngliche Konfiguration

`application.yml` neue Sektion:
```yaml
auth:
  jwt:
    secret: ${AUTH_JWT_SECRET:}      # Pflicht in prod (fail-fast)
    expiry: 604800                   # 7 Tage
  cookie:
    name: session
    secure: ${AUTH_COOKIE_SECURE:true}
```

`docker-compose.yml`: `AUTH_JWT_SECRET` als Pflicht-ENV, `AUTH_COOKIE_SECURE=false` für lokales http.

## Ursprüngliche Akzeptanzkriterien

1. Aufruf von `/` ohne Cookie → automatisch redirect zu `/login`.
2. `/register` legt Konto an, setzt Cookie, redirected zu `/`.
3. Logout → Cookie cleared, redirect zu `/login`, alle weiteren Aufrufe redirecten.
4. Reload nach Login → bleibt eingeloggt, kein Flash auf `/login`.
5. Cookie manuell gelöscht → nächster API-Call liefert 401, FE redirected.
6. Alle `/api/v1/projects/**`-Endpoints geben ohne gültiges Cookie 401.
7. Doppelte Registrierung → 409 + Toast „Email bereits registriert".
8. App im prod-Profile ohne `AUTH_JWT_SECRET` → startet nicht.
9. Backend-Tests grün: `UserStorageTest`, `JwtServiceTest`, `UserServiceTest`, `AuthControllerTest`, `JwtAuthenticationFilterTest`, `SecurityIntegrationTest`.

## Out of Scope

- Mitglieder-/Rollenmodell, Per-User-Projekt-Ownership
- Refresh-Tokens, Sliding Expiration
- Email-Verifikation, Password-Reset, Account-Löschung, Profilseite, Admin-UI
- OAuth/SSO/SAML, Rate-Limiting (eigenes Folge-Feature)
- Frontend-Unit-Tests (kein Test-Runner konfiguriert)

## Betroffene Dateien

**Neu (Backend):**
- `backend/src/main/kotlin/com/agentwork/productspecagent/auth/{User,UserStorage,UserService,JwtService,AuthCookieService,AuthController,JwtAuthenticationFilter,AuthExceptions}.kt`
- `backend/src/test/kotlin/com/agentwork/productspecagent/auth/{UserStorage,JwtService,UserService,AuthController,JwtAuthenticationFilter}Test.kt` + `SecurityIntegrationTest.kt`

**Geändert (Backend):**
- `backend/src/main/kotlin/com/agentwork/productspecagent/config/SecurityConfig.kt`
- `backend/src/main/resources/application.yml` + `application-dev.yml`
- `backend/build.gradle.kts` (jjwt-Dependencies)
- `docker-compose.yml`

**Neu (Frontend):**
- `frontend/src/proxy.ts`
- `frontend/src/app/(auth)/{login,register}/page.tsx` + `frontend/src/app/(auth)/layout.tsx`
- `frontend/src/components/auth/{LoginForm,RegisterForm,LogoutButton}.tsx`
- `frontend/src/lib/auth/api.ts`
- `frontend/src/lib/stores/auth-store.ts`

**Geändert (Frontend):**
- `frontend/src/lib/api.ts` — `credentials: 'include'` + 401-Handler
- `frontend/src/app/layout.tsx` — Auth-Provider mountet `GET /api/v1/auth/me` und initialisiert `auth-store`
- `frontend/src/components/layout/AppShell.tsx` — Logout-Button + User-Email-Anzeige

**Geändert (Docs):**
- `docs/architecture/auth.md` (komplett überarbeiten)
- `docs/features/00-feature-set-overview.md` (Zeile 42 ergänzen)

# Feature 42 — Login-Gate (DONE)

**Implementiert:** 2026-05-05
**Branch:** `feat/login-gate`
**Spec:** [`42-login-gate.md`](42-login-gate.md)
**Plan:** historisches Superpowers-Artefakt, nicht versioniert

> **Hinweis:** Diese Done-Datei dokumentiert die ursprüngliche Feature-42-Implementierung mit Cookie-Transport. Der aktuelle Auth-Transport wurde in [Feature 57 — Session-Storage Bearer Auth](57-session-storage-bearer-auth.md) auf `Authorization: Bearer <token>` + `sessionStorage` umgestellt. Für aktuelle Architekturdetails siehe [Architecture: Authentifizierung](../architecture/auth.md).

## Zusammenfassung der ursprünglichen Implementierung

Login-Gate vor jeder API- und UI-Aktion. Selbst-Registrierung mit Email + Passwort (≥ 8 Zeichen, BCrypt-Strength 10). Stateless JWT (HMAC-SHA256, 7 Tage fix) in einem `httpOnly; SameSite=Lax`-Session-Cookie. Backend: Spring Security 6 mit eigenem `OncePerRequestFilter`. Frontend: Next.js 16 Proxy für optimistic Cookie-Check + `AuthProvider` für Boot-Hydration. Alle eingeloggten User teilen einen gemeinsamen Workspace (kein Member-/Rollen-Modell, keine Per-User-Projekt-Ownership — wie geplant).

Backend-Test-Suite: 462 grün / 1 pre-existing Failure (`HandoffControllerTest` CLAUDE.md Content-Assertion — nichts mit Auth). Frontend baut clean, alle neuen Routes (`/login`, `/register`) im Build erkannt, Proxy als Middleware aktiv.

## Commits (chronologisch)

| Plan-Task | SHA | Inhalt |
|---|---|---|
| 1 | `fcc5f74` | jjwt 0.12.6 deps + `AuthProperties` + `application.yml`-Block |
| 2 | `420ff0b` | `User` Domain + `AuthCredentials`/`AuthMeResponse` DTOs |
| 3 | `2e078a8` | `UserStorage` (ObjectStore-basiert, Email-Index als Pointer-File) + 6 Tests |
| 4 | `03d7c5a` | `JwtService` (HMAC-SHA256, fail-fast bei leerem Secret) + 6 Tests |
| 5 | `ec1d360` | `UserService` (Email-/Password-Validation, BCrypt) + 10 Tests + `PasswordEncoder` Bean |
| 6 | `19dd08e` | `AuthCookieService` (set/clear via `ResponseCookie`) |
| 7 | `fd0477d` | `JwtAuthenticationFilter` (Cookie → SecurityContext) + 4 Tests + mockito-kotlin Dep |
| 8 | `1d8a887` | `SecurityConfig` umgestellt: `permitAll` für `/api/v1/auth/...` + `/api/health`, sonst `authenticated()` |
| 8b | `97076cf` | Test-Bootstrap-Fix: Test-`application.yml` + `TestMockMvcSecurityConfig` + `@WithMockUser` auf 18 Controller-Tests |
| 9 | `642535f` | `AuthController` (4 Endpoints) + `GlobalExceptionHandler` Erweiterung + 9 Tests |
| 10 | `8dab8a6` | `SecurityIntegrationTest` (3 End-to-End-Cases) |
| 11 | `d3573f4` | `application-dev.yml` + `docker-compose.yml` (backend-Service neu) |
| 12 | `ec8cd5c` | `lib/api.ts`: `apiFetch` mit `credentials: 'include'` + 401-Handler + Auth-API-Helpers |
| 13 | `dadfe2f` | `auth-store` (Zustand) |
| 14 | `70b4e7a` | `AuthProvider` + Wiring im Root-Layout |
| 15 | `e62b93b` | `proxy.ts` (Cookie-basiertes Redirect) |
| 16 | `f0bcbb7` | `(auth)/login/page.tsx` + `LoginForm` |
| 17 | `1b6ff55` | `(auth)/register/page.tsx` + `RegisterForm` |
| 18 | `e03c80b` | `LogoutButton` + Integration in `AppShell`-IconRail |
| Hotfix 1 | `a2f9eba` | `start.sh` exportiert `AUTH_JWT_SECRET` + `AUTH_COOKIE_SECURE` |
| Hotfix 2 | `242ce06` | `credentials: 'include'` in `exportProject` + `exportHandoff` (Blob-Endpoints) |
| Hotfix 3 | `36122a1` | `credentials: 'include'` für 9 weitere raw-fetch-Stellen (Project/Task/Document/AssetBundle/DesignBundle CRUD + File-Fetch) |

## Abweichungen vom Plan

### Architektur
- **Storage**: Plan beschrieb `data/users/...`-Pfade (java.nio.file). Tatsächliches Projekt-Pattern ist `ObjectStore` (Feature 31, S3-kompatibel). `UserStorage` folgt dem Pattern: Keys `users/{userId}.json` + `users/_index/email-{lowercase-email}.json`. Tests laufen gegen MinIO-Testcontainer via `S3TestSupport`.
- **Frontend-API-Helpers**: Plan listete `lib/auth/api.ts`. Konvention im Projekt ist „kein fetch außerhalb `lib/api.ts`". Auth-Helpers (`authLogin`/`authRegister`/`authLogout`/`authMe`) wurden in `lib/api.ts` aufgenommen.
- **Packages**: Plan listete alles unter `auth/`. Plan-Implementierung folgt Projektkonvention: `domain/User.kt`, `storage/UserStorage.kt`, `service/UserService.kt` + `service/AuthExceptions.kt`, `auth/{JwtService, AuthCookieService, JwtAuthenticationFilter}`, `api/AuthController.kt`. Auth-Errors gehen in den existierenden `GlobalExceptionHandler`.

### Test-Infrastruktur (T8b)
Plan-Task 8 sah `@WithMockUser` als optionale Maßnahme bei Test-Regressionen vor. Tatsächlich nötig wurde:
- `backend/src/test/resources/application.yml` um einen Default-`auth.jwt.secret` ergänzen (sonst stirbt der Spring-Context bei jedem `@SpringBootTest` an `JwtService`-Init)
- Neuer Helper `backend/src/test/kotlin/.../config/TestMockMvcSecurityConfig.kt` mit `@Bean MockMvcBuilderCustomizer`, der `SecurityMockMvcConfigurers.springSecurity()` auf jede `@AutoConfigureMockMvc`-Instanz anwendet (Spring Security 6+/7 nutzt `SecurityContextHolderFilter`, daher reicht `@WithMockUser` allein nicht)
- `@WithMockUser` als Klassen-Annotation auf 18 betroffenen Controller-Tests

### `/api/v1/auth/me` ist nicht permitAll
Plan-Task 9 sah `/api/v1/auth/**` komplett `permitAll`. Damit hätte `/me` ohne Cookie 200 mit leerem Principal geliefert (nicht 401 wie spec'd). Bei T9 wurde die Konfiguration präziser gemacht: `register`/`login`/`logout` bleiben `permitAll`, `/me` fordert Auth. Test deckt das.

### docker-compose
Original-`docker-compose.yml` hatte nur `minio` + `minio-init`, **kein** `backend`-Service. Plan-Task 11 sagte „add env vars to backend service" — das implizierte Existenz. Implementer hat den ganzen `backend`-Service angelegt (`build: ./backend`, ports 8080, depends_on minio). `backend/Dockerfile` existiert, das ist also funktional. Frontend-Service fehlt nach wie vor (CLAUDE.md erwähnt einen, ist aber nie gepflegt worden — bewusst nicht ergänzt, ist nicht Login-Gate-Scope).

### Minor-Stilistik (kein Funktions-Impact)
- `UserService.kt:27` nutzt `passwordEncoder.encode(password)!!`. Spring's `PasswordEncoder.encode` ist Java-`String` (non-null), Kotlin sieht's als Platform-Type `String!`. `!!` ist defensiv-redundant, war nicht im Plan. Belassen — kein Blocker, ein-Zeichen-Cleanup für später.

## Hotfixes während Smoke Test

### `start.sh` aktivierte den dev-Profile nie
Original `start.sh` ruft `./gradlew bootRun` ohne `--args='--spring.profiles.active=dev'` und ohne `AUTH_JWT_SECRET` Env-Var. Backend startete daher mit leerem JWT-Secret und scheiterte fail-fast. Hotfix `a2f9eba`: `start.sh` exportiert `AUTH_JWT_SECRET` und `AUTH_COOKIE_SECURE` (mit User-Override-Möglichkeit), `application.yml` greift sie über `${AUTH_JWT_SECRET:}` ab.

### Raw-fetch-Stellen ohne `credentials: 'include'`
Mehrere `lib/api.ts`-Funktionen umgehen `apiFetch`, weil sie FormData posten oder Blobs/Responses zurückgeben. Sie alle sendeten kein Cookie → 401 → Funktionen kaputt:
- `exportProject` + `exportHandoff` (ZIP-Downloads) — `242ce06`
- `deleteProject`, `deleteTask` — `36122a1`
- `uploadDocument`, `deleteDocument` — `36122a1`
- `uploadAssetBundle`, `deleteAssetBundle`, `fetchAssetBundleFile` — `36122a1`
- `uploadDesignBundle`, `getDesignBundle`, `deleteDesignBundle` — `36122a1`

Alle 11 Stellen jetzt mit `credentials: 'include'` und `if (res.status === 401) onUnauthorized?.()`. Lessons learned: `apiFetch` allein reicht nicht — beim Aktivieren eines Auth-Gates müssen ALLE fetch-Aufrufe geprüft werden.

## Offene Punkte / Tech Debt

1. **`UserService.kt:27`**: `!!` auf `passwordEncoder.encode(password)` ist redundant (Platform-Type-Defensive). Können wir bei Gelegenheit entfernen.
2. **`HandoffControllerTest > POST export embeds project name sync URL and behavioral guidelines into CLAUDE md`** — pre-existing Failure (Content-Assertion-Bug, nicht Auth-bezogen). Existierte schon vor diesem Branch.
3. **Frontend-Service in `docker-compose.yml` fehlt** — CLAUDE.md erwähnt einen, war aber nie gepflegt. Nicht im Login-Gate-Scope.
4. **Rate-Limiting auf `/api/v1/auth/login`** — nicht im Scope, eigenes Folge-Feature wenn nötig.
5. **Kein Server-Side Token-Invalidate beim Logout** — Cookie wird nur clientseitig gelöscht. Bei kompromittiertem Token bis zur natürlichen Expiry (7 d) gültig. Akzeptiert für interne Nutzung.
6. **Pre-existing Typos in User-Bundles**: `asset-bundles/{dotNet,monolith,postgresql}-bundle/mainfest.json` (Tippfehler `mainfest`) wurden während Smoke Tests in `manifest.json` umbenannt. Diese Bundles wurden auch neu gezippt (manifest am ZIP-Root statt im Wrapping-Ordner für `stitch-frontend-bundle`). Sind alles Working-Tree-Artifacts des Users; Login-Gate-Code unbeeinflusst.

## Akzeptanzkriterien (Spec)

| # | Kriterium | Status |
|---|---|---|
| 1 | Aufruf `/` ohne Cookie → redirect zu `/login` | ✅ via `proxy.ts` |
| 2 | `/register` legt Konto an → redirect zu `/projects` | ✅ |
| 3 | Logout → Cookie gecleart, redirect zu `/login` | ✅ |
| 4 | Reload nach Login → bleibt eingeloggt, kein Flash auf `/login` | ✅ via `AuthProvider` + `auth-store.initialize()` |
| 5 | Cookie manuell gelöscht → 401, FE redirected | ✅ via globalen 401-Handler in `lib/api.ts` |
| 6 | Alle `/api/v1/projects/**` ohne Cookie → 401 | ✅ verifiziert via `SecurityIntegrationTest` |
| 7 | Doppelte Registrierung → 409 + „Email bereits registriert" | ✅ |
| 8 | App ohne `AUTH_JWT_SECRET` (prod) → startet nicht | ✅ via fail-fast in `JwtService.<init>` |
| 9 | Backend-Tests grün | ✅ 462/463 (1 pre-existing, nicht-Auth) |

## Smoke-Test-Status

User hat den Smoke Test im Browser durchgespielt. Folgende Probleme aufgedeckt + gefixt während des Tests:
- start.sh-Bug (siehe Hotfix 1)
- Export-Button (siehe Hotfix 2)
- Asset-Bundle-Delete + alle anderen Bulk-Operationen (siehe Hotfix 3)
- User-Tippfehler in eigenen Bundle-Files (manuell behoben, Working Tree)

Mit allen Hotfixes ist die App nutzbar.

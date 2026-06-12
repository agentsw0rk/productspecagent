# Hackathon Lock Design

## Goal

Add a backend-controlled frontend lock mode for the CVDT Hackathon. When enabled, regular users see a full-screen countdown page for **Hackathon CVDT** until **2026-06-26 09:00 Europe/Berlin**. The lock is configured from backend `application.yml` and exposed through the existing public config endpoint. Admin users can bypass the lock and continue using the application.

## Decisions

- Visual direction: **Broadcast / Control Room**.
- Target time: `2026-06-26T09:00:00+02:00`.
- Admin bypass: enabled.
- Backend remains the source of truth for whether the lock is active.
- The frontend blocks the application shell and all normal pages for non-admin users while the lock is active.

## Context

The project already has:

- `backend/src/main/resources/application.yml` for runtime configuration.
- `ConfigController` exposing `/api/v1/config/features`.
- `@ConfigurationPropertiesScan` enabled in `ProductSpecAgentApplication`.
- A global `frontend/src/app/layout.tsx` wrapping all pages with `AuthProvider` and `AppShell`.
- `frontend/src/proxy.ts` for route-level auth redirects.
- `AuthMeResponse` used by `/api/v1/auth/me`.

Context7 findings:

- Next.js 16 uses `proxy.ts` for request interception and matcher-based route handling. This is suitable for cookie-auth route protection, but not ideal for this lock because the lock state is backend-configured and admin status requires a backend auth lookup.
- Spring Boot 4 supports immutable Kotlin `@ConfigurationProperties` data classes bound from YAML and environment variables. This matches existing project patterns.

## Backend Design

Add `HackathonProperties`:

```kotlin
@ConfigurationProperties(prefix = "hackathon")
data class HackathonProperties(
    val lock: Lock = Lock(),
) {
    data class Lock(
        val enabled: Boolean = false,
        val title: String = "Hackathon CVDT",
        val eventDate: LocalDate = LocalDate.parse("2026-06-26"),
        val targetAt: OffsetDateTime = OffsetDateTime.parse("2026-06-26T09:00:00+02:00"),
        val adminBypassEnabled: Boolean = true,
    )
}
```

Extend `application.yml`:

```yaml
hackathon:
  lock:
    enabled: ${HACKATHON_LOCK_ENABLED:false}
    title: ${HACKATHON_LOCK_TITLE:Hackathon CVDT}
    event-date: ${HACKATHON_LOCK_EVENT_DATE:2026-06-26}
    target-at: ${HACKATHON_LOCK_TARGET_AT:2026-06-26T09:00:00+02:00}
    admin-bypass-enabled: ${HACKATHON_LOCK_ADMIN_BYPASS_ENABLED:true}
```

Extend `/api/v1/config/features` response with:

```json
{
  "graphmeshEnabled": false,
  "hackathonLock": {
    "enabled": true,
    "title": "Hackathon CVDT",
    "eventDate": "2026-06-26",
    "targetAt": "2026-06-26T09:00:00+02:00",
    "adminBypassEnabled": true
  }
}
```

Allow unauthenticated access to `GET /api/v1/config/features`, since the lock page must be renderable before login.

Extend `AuthMeResponse` with `admin: Boolean`. Compute it using the existing admin-email configuration semantics so the frontend can decide whether to bypass the lock.

## Frontend Design

Add API types/functions for config features:

- `AppFeaturesConfig`
- `HackathonLockConfig`
- `getFeaturesConfig()`

Add `HackathonLockProvider` near the root layout:

- Fetches `/api/v1/config/features` on mount.
- Uses the existing auth store state from `AuthProvider`.
- If lock config cannot be loaded, fail open and render the app. This avoids accidentally bricking the frontend during backend outages.
- If `hackathonLock.enabled === false`, render the app.
- If lock is active and `adminBypassEnabled === true` and `auth.user?.admin === true`, render the app.
- Otherwise render the lock page instead of `AppShell`.

The provider should wrap `AppShell` inside `AuthProvider`:

```tsx
<AuthProvider>
  <HackathonLockProvider>
    <AppShell>{children}</AppShell>
  </HackathonLockProvider>
</AuthProvider>
```

## Lock Page

The lock page is a full-screen, standalone UI. It must not render the sidebar or underlying app chrome.

Content:

- `Hackathon CVDT`
- `26. Juni 2026`
- Live countdown to `targetAt` with days, hours, minutes, seconds.
- Broadcast/control-room styling: dark background, bright lime signal accent, technical labels, restrained animation.
- When countdown reaches zero, show “Hackathon gestartet” rather than negative values.

Accessibility:

- Use semantic heading.
- Countdown numbers have visible labels.
- Avoid relying on color alone.
- Respect responsive layout on mobile and desktop.

## Admin Bypass UX

For admins, the app loads normally while the lock is active. A small non-blocking indicator may be shown in the shell or omitted in the first iteration. The required behavior is bypass, not admin lock management.

Admin users still need to be able to reach `/login` when not authenticated. After login, `auth/me` exposes `admin: true`, and the provider renders the app.

## Testing

Backend:

- `ConfigControllerTest` verifies `hackathonLock` is present and binds configured values.
- Security test verifies `GET /api/v1/config/features` is public.
- Auth test verifies `/api/v1/auth/me` includes `admin`.

Frontend:

- Unit/component-oriented Playwright test verifies active lock renders the countdown page for guests.
- Test verifies inactive lock renders normal app/start page.
- Test verifies admin user bypasses active lock.
- Countdown logic test covers target passed state.

Verification commands:

- `cd backend && ./gradlew test --tests '*ConfigControllerTest' --tests '*AuthControllerTest' --tests '*SecurityIntegrationTest'`
- `cd frontend && npm run test:e2e -- hackathon-lock.spec.ts auth-api.spec.ts`
- `cd frontend && npm run lint`
- `cd frontend && npm run build`

## Out of Scope

- Runtime admin UI for changing the lock.
- Backend API blocking of domain endpoints.
- Role-management UI.
- Persisting lock changes outside application configuration.

## Risks

- If the config endpoint is accidentally protected, guests cannot render the lock page. Mitigation: explicit security test.
- If the lock provider waits indefinitely for auth/config, users may see a blank screen. Mitigation: bounded loading state and fail-open on config errors.
- If admin status is computed inconsistently across backend paths, bypass may fail. Mitigation: reuse the same admin-email configuration semantics in `auth/me`.

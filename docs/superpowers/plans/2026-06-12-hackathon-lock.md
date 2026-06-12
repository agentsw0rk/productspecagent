# Hackathon Lock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a backend-controlled CVDT Hackathon lock that shows a full-screen countdown until 2026-06-26 09:00 Europe/Berlin for non-admin users while allowing admins to bypass it.

**Architecture:** Spring Boot exposes lock config through the existing public config endpoint and includes `admin` in auth responses. The Next.js root layout wraps the app shell in a lock provider that fetches config/auth state and renders a standalone Broadcast / Control Room countdown page for non-admin users while the lock is active.

**Tech Stack:** Kotlin 2.3, Spring Boot 4, MockMvc, Next.js 16 App Router, React 19, TypeScript, Playwright, Tailwind CSS 4.

---

## File Structure

- Create `backend/src/main/kotlin/com/agentwork/productspecagent/config/HackathonProperties.kt`: type-safe `hackathon.lock` configuration.
- Modify `backend/src/main/resources/application.yml`: default lock settings and env overrides.
- Modify `backend/src/main/kotlin/com/agentwork/productspecagent/api/ConfigController.kt`: include `hackathonLock`.
- Modify `backend/src/main/kotlin/com/agentwork/productspecagent/config/SecurityConfig.kt`: make `GET /api/v1/config/features` public.
- Modify `backend/src/main/kotlin/com/agentwork/productspecagent/domain/AuthDtos.kt`: add `admin`.
- Modify `backend/src/main/kotlin/com/agentwork/productspecagent/api/AuthController.kt`: compute and return `admin`.
- Modify backend tests: `ConfigControllerTest.kt`, `AuthControllerTest.kt`, `SecurityIntegrationTest.kt`.
- Modify `frontend/src/lib/api.ts`: add config types and fetcher, extend `AuthMe.admin`.
- Create `frontend/src/lib/hackathon-countdown.ts`: pure countdown calculation.
- Create `frontend/src/components/hackathon/HackathonLockPage.tsx`: visual lock page.
- Create `frontend/src/components/hackathon/HackathonLockProvider.tsx`: config/auth gate.
- Modify `frontend/src/app/layout.tsx`: wrap `AppShell` with provider.
- Add `frontend/e2e/hackathon-lock.spec.ts`: frontend lock and bypass tests.
- Add `docs/features/56-hackathon-lock-done.md`: completion note.

---

### Task 1: Backend Lock Config and Admin Flag

**Files:**
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/config/HackathonProperties.kt`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/kotlin/com/agentwork/productspecagent/api/ConfigController.kt`
- Modify: `backend/src/main/kotlin/com/agentwork/productspecagent/config/SecurityConfig.kt`
- Modify: `backend/src/main/kotlin/com/agentwork/productspecagent/domain/AuthDtos.kt`
- Modify: `backend/src/main/kotlin/com/agentwork/productspecagent/api/AuthController.kt`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/api/ConfigControllerTest.kt`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/api/AuthControllerTest.kt`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/api/SecurityIntegrationTest.kt`

- [ ] **Step 1: Write failing backend tests**

Update `ConfigControllerTest` to assert that `/api/v1/config/features` returns `hackathonLock.enabled`, `title`, `eventDate`, `targetAt`, and `adminBypassEnabled`. Remove class-level `@WithMockUser` so the endpoint is proven public.

Add to `AuthControllerTest`:

```kotlin
@Test
fun `me returns admin true for configured admin email`() {
    val res = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body("admin@example.com", "password123")))
        .andReturn().response
    val cookie = res.getCookie("session")!!
    mvc.perform(get("/api/v1/auth/me").cookie(cookie))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.email").value("admin@example.com"))
        .andExpect(jsonPath("$.admin").value(true))
}
```

Add to `SecurityIntegrationTest`:

```kotlin
@Test
fun `features config endpoint is public`() {
    mvc.perform(get("/api/v1/config/features"))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.hackathonLock").exists())
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
cd backend
./gradlew test --tests '*ConfigControllerTest' --tests '*AuthControllerTest' --tests '*SecurityIntegrationTest'
```

Expected: fail because `hackathonLock` and `admin` are not present and config endpoint is not explicitly public.

- [ ] **Step 3: Implement backend config**

Create `HackathonProperties.kt`:

```kotlin
package com.agentwork.productspecagent.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.LocalDate
import java.time.OffsetDateTime

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

Add to `application.yml`:

```yaml
hackathon:
  lock:
    enabled: ${HACKATHON_LOCK_ENABLED:false}
    title: ${HACKATHON_LOCK_TITLE:Hackathon CVDT}
    event-date: ${HACKATHON_LOCK_EVENT_DATE:2026-06-26}
    target-at: ${HACKATHON_LOCK_TARGET_AT:2026-06-26T09:00:00+02:00}
    admin-bypass-enabled: ${HACKATHON_LOCK_ADMIN_BYPASS_ENABLED:true}
```

- [ ] **Step 4: Implement backend responses and security**

Inject `HackathonProperties` into `ConfigController` and return:

```kotlin
"hackathonLock" to mapOf(
    "enabled" to hackathon.lock.enabled,
    "title" to hackathon.lock.title,
    "eventDate" to hackathon.lock.eventDate.toString(),
    "targetAt" to hackathon.lock.targetAt.toString(),
    "adminBypassEnabled" to hackathon.lock.adminBypassEnabled,
)
```

Update `SecurityConfig`:

```kotlin
.requestMatchers(HttpMethod.GET, "/api/v1/config/features").permitAll()
```

Extend `AuthMeResponse`:

```kotlin
data class AuthMeResponse(
    val userId: String,
    val email: String,
    val admin: Boolean = false,
)
```

In `AuthController`, inject `AuthProperties` and compute:

```kotlin
private fun isAdmin(email: String): Boolean {
    val normalizedEmail = email.trim().lowercase()
    return authProperties.adminEmails.any { configured ->
        val normalizedConfigured = configured.trim().lowercase()
        normalizedConfigured == "*" || normalizedConfigured == normalizedEmail
    }
}
```

Return `AuthMeResponse(user.id, user.email, isAdmin(user.email))` for register/login and `AuthMeResponse(userId, email, isAdmin(email))` for `me`.

- [ ] **Step 5: Run backend tests and commit**

Run:

```bash
cd backend
./gradlew test --tests '*ConfigControllerTest' --tests '*AuthControllerTest' --tests '*SecurityIntegrationTest'
```

Expected: PASS.

Commit:

```bash
git add backend/src/main/kotlin/com/agentwork/productspecagent/config/HackathonProperties.kt backend/src/main/resources/application.yml backend/src/main/kotlin/com/agentwork/productspecagent/api/ConfigController.kt backend/src/main/kotlin/com/agentwork/productspecagent/config/SecurityConfig.kt backend/src/main/kotlin/com/agentwork/productspecagent/domain/AuthDtos.kt backend/src/main/kotlin/com/agentwork/productspecagent/api/AuthController.kt backend/src/test/kotlin/com/agentwork/productspecagent/api/ConfigControllerTest.kt backend/src/test/kotlin/com/agentwork/productspecagent/api/AuthControllerTest.kt backend/src/test/kotlin/com/agentwork/productspecagent/api/SecurityIntegrationTest.kt
git commit -m "feat(config): expose hackathon lock"
```

---

### Task 2: Frontend Lock Gate and Countdown UI

**Files:**
- Modify: `frontend/src/lib/api.ts`
- Create: `frontend/src/lib/hackathon-countdown.ts`
- Create: `frontend/src/components/hackathon/HackathonLockPage.tsx`
- Create: `frontend/src/components/hackathon/HackathonLockProvider.tsx`
- Modify: `frontend/src/app/layout.tsx`
- Test: `frontend/e2e/hackathon-lock.spec.ts`

- [ ] **Step 1: Write failing frontend tests**

Create `frontend/e2e/hackathon-lock.spec.ts` with three tests:

1. Active lock + guest renders heading `Hackathon CVDT` and countdown labels.
2. Inactive lock renders the normal public start page heading `Product Spec Agent`.
3. Active lock + `auth/me` returning `{ admin: true }` renders the app instead of the lock.

Mock `GET ${API_BASE}/api/v1/config/features` and `GET ${API_BASE}/api/v1/auth/me` with Playwright routes.

- [ ] **Step 2: Run frontend tests and verify RED**

Run:

```bash
cd frontend
npm run test:e2e -- hackathon-lock.spec.ts
```

Expected: fail because the lock provider/page do not exist.

- [ ] **Step 3: Implement config API and countdown calculation**

In `frontend/src/lib/api.ts`, add:

```ts
export interface HackathonLockConfig {
  enabled: boolean;
  title: string;
  eventDate: string;
  targetAt: string;
  adminBypassEnabled: boolean;
}

export interface AppFeaturesConfig {
  graphmeshEnabled: boolean;
  hackathonLock: HackathonLockConfig;
}

export async function getFeaturesConfig(): Promise<AppFeaturesConfig> {
  return apiFetch<AppFeaturesConfig>("/api/v1/config/features", { suppressUnauthorizedHandler: true });
}
```

Extend `AuthMe` with `admin: boolean`.

Create `hackathon-countdown.ts` exporting:

```ts
export interface CountdownParts {
  days: number;
  hours: number;
  minutes: number;
  seconds: number;
  complete: boolean;
}

export function getCountdownParts(targetAt: string, now = new Date()): CountdownParts {
  const remainingMs = new Date(targetAt).getTime() - now.getTime();
  if (remainingMs <= 0) return { days: 0, hours: 0, minutes: 0, seconds: 0, complete: true };
  const totalSeconds = Math.floor(remainingMs / 1000);
  const days = Math.floor(totalSeconds / 86400);
  const hours = Math.floor((totalSeconds % 86400) / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return { days, hours, minutes, seconds, complete: false };
}
```

- [ ] **Step 4: Implement lock provider and page**

`HackathonLockProvider`:

- Fetches config on mount.
- Reads `user` and `status` from `useAuthStore`.
- Shows a simple full-screen loading state while config is loading.
- Fails open if config fetch errors.
- Renders children if lock disabled.
- Renders children if admin bypass enabled and `user.admin === true`.
- Otherwise renders `HackathonLockPage`.

`HackathonLockPage`:

- Broadcast/control-room style.
- Heading: `Hackathon CVDT`.
- Date: `26. Juni 2026`.
- Countdown cards for Tage, Stunden, Minuten, Sekunden.
- Shows `Hackathon gestartet` when `complete` is true.

Modify `layout.tsx`:

```tsx
<AuthProvider>
  <HackathonLockProvider>
    <AppShell>{children}</AppShell>
  </HackathonLockProvider>
</AuthProvider>
```

- [ ] **Step 5: Run frontend tests and commit**

Run:

```bash
cd frontend
npm run test:e2e -- hackathon-lock.spec.ts auth-api.spec.ts
npm run lint
npm run build
```

Expected: Playwright tests pass, lint has no errors, build succeeds.

Commit:

```bash
git add frontend/src/lib/api.ts frontend/src/lib/hackathon-countdown.ts frontend/src/components/hackathon/HackathonLockPage.tsx frontend/src/components/hackathon/HackathonLockProvider.tsx frontend/src/app/layout.tsx frontend/e2e/hackathon-lock.spec.ts
git commit -m "feat(frontend): add hackathon lock screen"
```

---

### Task 3: Documentation, Final Verification, and Done File

**Files:**
- Create: `docs/features/56-hackathon-lock-done.md`

- [ ] **Step 1: Write done documentation**

Create `docs/features/56-hackathon-lock-done.md` containing:

```markdown
# Feature 56: Hackathon Lock - Done

## Summary

Implemented a backend-controlled Hackathon CVDT lock mode. When enabled, non-admin users see a full-screen Broadcast / Control Room countdown page until 2026-06-26 09:00 Europe/Berlin. Admin users can bypass the lock.

## Implemented

- Backend `hackathon.lock` configuration in `application.yml`.
- Public `/api/v1/config/features` response includes `hackathonLock`.
- `/api/v1/auth/me` includes `admin`.
- Frontend root lock provider.
- Broadcast-style countdown page.
- Regression tests for backend config/auth/security and frontend lock behavior.

## Deviations

- No admin UI was added for toggling the lock at runtime. The lock is controlled through backend configuration and environment variables.

## Open Items

- Deployments must set `HACKATHON_LOCK_ENABLED=true` when the lock should be active.
```

- [ ] **Step 2: Run final verification**

Run:

```bash
cd backend
./gradlew test --tests '*ConfigControllerTest' --tests '*AuthControllerTest' --tests '*SecurityIntegrationTest'
cd ../frontend
npm run test:e2e -- hackathon-lock.spec.ts auth-api.spec.ts
npm run lint
npm run build
```

Expected: all test/build commands exit 0; lint may report existing warnings but no errors.

- [ ] **Step 3: Commit docs**

```bash
git add docs/features/56-hackathon-lock-done.md
git commit -m "docs: document hackathon lock"
```

- [ ] **Step 4: Final review**

Review `git diff HEAD~3..HEAD --stat`, `git status --short --branch`, and confirm only intended backend, frontend, test, spec, plan, and done-doc files changed.

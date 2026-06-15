# Session-Storage Bearer Auth Design

**Date:** 2026-06-15  
**Status:** Approved design, pending implementation plan  
**Related:** [Feature 42 — Login-Gate](../../features/42-login-gate.md), [Feature 42 done](../../features/42-login-gate-done.md), [Auth architecture](../../architecture/auth.md)

## Problem

Feature 42 implemented the login gate with a stateless JWT stored in an `httpOnly` session cookie. That is secure against token reads from browser JavaScript, but the deployment has run into cookie/CORS/domain issues. The product decision is to remove cookie-based authentication and store the JWT in browser `sessionStorage` instead.

This is a follow-up feature to Feature 42, not a reimplementation of the login gate. User storage, password hashing, JWT signing, admin-email handling, and the existing auth endpoints remain the foundation.

## Goals

- Replace cookie transport with `Authorization: Bearer <jwt>`.
- Store the JWT in `sessionStorage`, not `localStorage`.
- Keep login scoped to a browser tab/window and clear it when that session ends.
- Keep the existing login/register/logout/me user experience.
- Keep backend authentication stateless.
- Make all frontend API calls consistently attach the bearer token.
- Document the security tradeoff explicitly.

## Non-Goals

- No refresh tokens.
- No server-side token invalidation on logout.
- No OAuth/SSO.
- No member or role model changes.
- No attempt to preserve server-side route protection through Next.js `proxy.ts`.
- No localStorage fallback.

## Architecture

### Backend

`POST /api/v1/auth/login` and `POST /api/v1/auth/register` will no longer set `Set-Cookie`. Each response returns the signed JWT alongside the existing user payload:

```json
{
  "token": "<jwt>",
  "userId": "...",
  "email": "...",
  "admin": false
}
```

`JwtAuthenticationFilter` will authenticate requests from the standard `Authorization` header when it starts with `Bearer `. The validated JWT payload continues to populate the Spring Security context with the user id as principal, admin authority when applicable, and `authEmail` as a request attribute.

`POST /api/v1/auth/logout` remains as a compatibility endpoint returning `204 No Content`. Since there is no server-side session and no refresh token, logout does not invalidate anything on the server. The effective logout happens in the browser by deleting the token from `sessionStorage`.

`AuthCookieService` and `auth.cookie.*` configuration are removed or made unused as part of this feature. The CORS configuration no longer needs credentialed cookie support for authentication.

### Frontend

A small auth-token helper will encapsulate `sessionStorage` access behind functions such as `getAuthToken`, `setAuthToken`, and `clearAuthToken`. It must guard browser-only APIs so imports are safe during server rendering.

`authLogin` and `authRegister` receive the token in the JSON response. `auth-store` writes that token to `sessionStorage` and keeps the user object in Zustand state. `authLogout` clears the token even if the backend logout call fails.

`apiFetch` becomes the primary JSON request path and attaches `Authorization: Bearer <token>` whenever a token is present. Existing manual fetches in `frontend/src/lib/api.ts` also need the same behavior through a shared helper, because several paths currently bypass `apiFetch` for deletes, downloads, blobs, and form-data uploads.

`credentials: "include"` is removed from auth-related requests unless a specific non-auth cookie use is discovered during implementation.

`frontend/src/proxy.ts` can no longer enforce protected pages because `sessionStorage` is not available to Next.js request-time proxy code. Route protection moves to the client auth layer.

### Client Route Gate

Public routes remain:

- `/`
- `/login`
- `/register`
- `/start-screen`

For protected routes, the client gate checks auth state after hydration:

- `loading`: show a neutral loading state, not protected data.
- `guest`: redirect to `/login?next=<currentPath>`.
- `authenticated`: render the protected content.

For `/login` and `/register`, an already authenticated user redirects to `/projects`.

## Data Flow

### Login/Register

1. User submits email and password.
2. Backend authenticates or registers the user.
3. Backend signs the JWT and returns `{ token, userId, email, admin }`.
4. Frontend stores `token` in `sessionStorage`.
5. Frontend stores the user payload in Zustand.
6. Frontend redirects to the requested `next` route or `/projects`.

### Reload

1. `AuthProvider` mounts client-side.
2. `auth-store.initialize()` reads the token from `sessionStorage`.
3. If no token exists, state becomes `guest`.
4. If a token exists, `/api/v1/auth/me` is called with `Authorization: Bearer <token>`.
5. On 200, state becomes `authenticated`.
6. On 401 or invalid token, the token is cleared and state becomes `guest`.

### API Calls

All API calls use this rule:

- Token present: send `Authorization: Bearer <token>`.
- Token absent: send no auth header and let protected endpoints return `401`.

The global 401 handler clears the token and redirects to `/login`, except for initialization calls like `/auth/me` that intentionally treat 401 as guest state.

### Logout

1. Frontend optionally calls `POST /api/v1/auth/logout` with the bearer token.
2. Frontend clears `sessionStorage` in a `finally` path.
3. Zustand state becomes `guest`.
4. User is redirected to `/login`.

## Error Handling

- Missing bearer token on protected endpoints returns `401`.
- Malformed `Authorization` header returns `401`.
- Invalid or expired JWT returns `401`.
- `/api/v1/auth/me` with no valid token returns `401`.
- Duplicate registration stays `409`.
- Invalid credentials stay generic and do not disclose whether an email exists.

## Security Notes

This feature intentionally trades cookie transport for browser storage:

- CSRF exposure is reduced because the browser no longer sends auth automatically through cookies.
- XSS impact is higher because JavaScript can read the JWT from `sessionStorage`.
- `sessionStorage` is chosen over `localStorage` so the token is tab-scoped and does not survive the browser session.
- No refresh token is introduced, avoiding a second long-lived credential in browser storage.
- The JWT expiry remains the existing backend value initially, even though browser persistence is now shorter in practice.

Follow-up hardening can consider a shorter JWT lifetime and stronger frontend XSS controls, but those are outside this feature.

## Affected Files

Expected backend changes:

- `backend/src/main/kotlin/com/agentwork/productspecagent/api/AuthController.kt`
- `backend/src/main/kotlin/com/agentwork/productspecagent/auth/JwtAuthenticationFilter.kt`
- `backend/src/main/kotlin/com/agentwork/productspecagent/auth/AuthCookieService.kt`
- `backend/src/main/kotlin/com/agentwork/productspecagent/config/AuthProperties.kt`
- `backend/src/main/kotlin/com/agentwork/productspecagent/config/SecurityConfig.kt`
- Auth and security tests under `backend/src/test/kotlin/com/agentwork/productspecagent/`

Expected frontend changes:

- `frontend/src/lib/api.ts`
- `frontend/src/lib/stores/auth-store.ts`
- `frontend/src/components/auth/AuthProvider.tsx`
- `frontend/src/proxy.ts`
- Login/register/logout components if their assumptions change
- Relevant Playwright specs under `frontend/e2e/`

Expected docs changes:

- `docs/architecture/auth.md`
- A new feature document at `docs/features/57-session-storage-bearer-auth.md`
- A done document after implementation

## Acceptance Criteria

1. Login returns a JWT in JSON and does not set an auth cookie.
2. Register returns a JWT in JSON and does not set an auth cookie.
3. Frontend stores the JWT in `sessionStorage`.
4. Frontend sends `Authorization: Bearer <jwt>` on protected API calls.
5. Backend accepts valid bearer tokens for protected endpoints.
6. Backend rejects protected endpoints without a bearer token.
7. Reload with a token in `sessionStorage` restores auth via `/auth/me`.
8. Reload without a token becomes guest state and redirects protected pages to `/login`.
9. Logout clears `sessionStorage` and redirects to `/login`.
10. Expired or invalid token clears local auth state after the next 401.
11. Existing admin-gated endpoints still honor `ROLE_ADMIN` derived from configured admin emails.
12. Cookie-specific auth tests are replaced with bearer-token tests.

## Verification Plan

Backend narrow checks:

```bash
cd backend && ./gradlew test --tests "*AuthControllerTest" --tests "*JwtAuthenticationFilterTest" --tests "*SecurityIntegrationTest"
```

Frontend checks:

```bash
cd frontend && npm run lint
cd frontend && npm run test:e2e -- auth-api.spec.ts
```

Broader regression may run full backend tests and the full Playwright suite if the environment is available.

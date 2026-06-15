# Session-Storage Bearer Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace cookie-based JWT auth with `sessionStorage` token storage in the browser and `Authorization: Bearer <jwt>` authentication on every protected API request.

**Architecture:** The backend remains stateless and continues using the existing JWT signing and user storage. Login/register return a token in JSON, the authentication filter reads the bearer token from the `Authorization` header, and the frontend stores the token in `sessionStorage`. Page protection moves from Next.js request-time proxy cookie checks to a client-side route gate in the auth provider.

**Tech Stack:** Kotlin 2.3, Spring Boot 4, Spring Security servlet filters, MockMvc, Next.js App Router, React 19, Zustand, TypeScript, Playwright.

---

## File Structure

Backend responsibilities:

- `backend/src/main/kotlin/com/agentwork/productspecagent/domain/AuthDtos.kt`: add a login/register response DTO with `token`, while keeping `/auth/me` on `AuthMeResponse`.
- `backend/src/main/kotlin/com/agentwork/productspecagent/api/AuthController.kt`: return token JSON for register/login and stop setting/clearing cookies.
- `backend/src/main/kotlin/com/agentwork/productspecagent/auth/JwtAuthenticationFilter.kt`: read and validate `Authorization: Bearer <jwt>`.
- `backend/src/main/kotlin/com/agentwork/productspecagent/auth/AuthCookieService.kt`: delete once unused.
- `backend/src/main/kotlin/com/agentwork/productspecagent/config/AuthProperties.kt`: remove cookie configuration.
- `backend/src/main/kotlin/com/agentwork/productspecagent/config/SecurityConfig.kt`: keep endpoint authorization; no structural auth policy change is needed.
- Backend auth tests: replace cookie assertions with bearer-token assertions.

Frontend responsibilities:

- `frontend/src/lib/auth-token.ts`: browser-safe `sessionStorage` wrapper.
- `frontend/src/lib/api.ts`: add token-aware headers for `apiFetch` and all direct fetch helpers.
- `frontend/src/lib/stores/auth-store.ts`: persist token on login/register, load it on initialize, clear it on logout/401.
- `frontend/src/components/auth/AuthProvider.tsx`: client route gate for protected pages and auth pages.
- `frontend/src/proxy.ts`: remove cookie-gate behavior because `sessionStorage` is not request-visible.
- `frontend/e2e/auth-api.spec.ts`: cover header injection, guest initialization behavior, and token clearing on 401.

Docs responsibilities:

- `docs/features/57-session-storage-bearer-auth.md`: feature spec for this follow-up feature.
- `docs/architecture/auth.md`: update architecture from cookie auth to bearer token auth.
- `docs/features/57-session-storage-bearer-auth-done.md`: write after implementation, during finishing.

---

### Task 1: Backend Contract Tests For Bearer Auth

**Files:**
- Modify: `backend/src/test/kotlin/com/agentwork/productspecagent/api/AuthControllerTest.kt`
- Modify: `backend/src/test/kotlin/com/agentwork/productspecagent/auth/JwtAuthenticationFilterTest.kt`
- Modify: `backend/src/test/kotlin/com/agentwork/productspecagent/api/SecurityIntegrationTest.kt`

- [ ] **Step 1: Update `AuthControllerTest` imports**

Remove cookie-specific imports and add JSON token extraction:

```kotlin
import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
```

Delete these imports if present:

```kotlin
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie as cookieM
```

Keep `header`, `jsonPath`, and `status`.

- [ ] **Step 2: Replace register/login cookie assertions with token JSON assertions**

In `AuthControllerTest`, replace the register and login tests with:

```kotlin
@Test
fun `register returns 201 with token and user info`() {
    mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body("alice@example.com", "password123")))
        .andExpect(status().isCreated)
        .andExpect(header().doesNotExist("Set-Cookie"))
        .andExpect(jsonPath("$.token").isString)
        .andExpect(jsonPath("$.token").isNotEmpty)
        .andExpect(jsonPath("$.email").value("alice@example.com"))
        .andExpect(jsonPath("$.userId").exists())
        .andExpect(jsonPath("$.admin").value(false))
}

@Test
fun `login with valid credentials returns 200 with token`() {
    mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body("login@example.com", "password123")))
    mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("login@example.com", "password123")))
        .andExpect(status().isOk)
        .andExpect(header().doesNotExist("Set-Cookie"))
        .andExpect(jsonPath("$.token").isString)
        .andExpect(jsonPath("$.token").isNotEmpty)
        .andExpect(jsonPath("$.email").value("login@example.com"))
}
```

- [ ] **Step 3: Replace `/me` cookie tests with bearer-header tests**

In `AuthControllerTest`, replace the `/me` and logout tests with:

```kotlin
private fun registerAndToken(email: String): String {
    val body = mvc.perform(post("/api/v1/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(email, "password123")))
        .andReturn()
        .response
        .contentAsString
    return JsonPath.read(body, "$.token")
}

@Test
fun `me without bearer token returns 401`() {
    mvc.perform(get("/api/v1/auth/me"))
        .andExpect(status().isUnauthorized)
}

@Test
fun `me with valid bearer token returns 200 with user info`() {
    val token = registerAndToken("me@example.com")
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.email").value("me@example.com"))
        .andExpect(jsonPath("$.token").doesNotExist())
}

@Test
fun `me returns admin true for configured admin email`() {
    val token = registerAndToken("admin@example.com")
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.email").value("admin@example.com"))
        .andExpect(jsonPath("$.admin").value(true))
}

@Test
fun `logout returns 204 without cookie mutation`() {
    val token = registerAndToken("lo@example.com")
    mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer $token"))
        .andExpect(status().isNoContent)
        .andExpect(header().doesNotExist("Set-Cookie"))
}
```

- [ ] **Step 4: Update `JwtAuthenticationFilterTest` to use bearer headers**

Remove `jakarta.servlet.http.Cookie` import. Replace all test request setup with headers:

```kotlin
@Test
fun `valid bearer token sets SecurityContext authentication`() {
    val token = jwt.sign("u-1", "a@b.com")
    val req = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer $token") }
    val res = MockHttpServletResponse()
    val chain: FilterChain = mock()

    filter.doFilter(req, res, chain)

    val auth = SecurityContextHolder.getContext().authentication
    assertNotNull(auth)
    assertEquals("u-1", auth!!.name)
    assertTrue(auth.isAuthenticated)
    assertEquals("a@b.com", req.getAttribute("authEmail"))
    verify(chain).doFilter(req, res)
}

@Test
fun `configured admin email receives admin authority`() {
    val adminProps = props.copy(adminEmails = listOf("admin@example.com"))
    val adminJwt = JwtService(adminProps)
    val adminFilter = JwtAuthenticationFilter(adminJwt, adminProps)
    val token = adminJwt.sign("u-admin", "admin@example.com")
    val req = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer $token") }
    val res = MockHttpServletResponse()
    val chain: FilterChain = mock()

    adminFilter.doFilter(req, res, chain)

    val auth = SecurityContextHolder.getContext().authentication
    assertNotNull(auth)
    assertTrue(auth!!.authorities.any { it.authority == "ROLE_ADMIN" })
    verify(chain).doFilter(req, res)
}

@Test
fun `missing authorization header leaves SecurityContext empty`() {
    val req = MockHttpServletRequest()
    val res = MockHttpServletResponse()
    val chain: FilterChain = mock()

    filter.doFilter(req, res, chain)

    assertNull(SecurityContextHolder.getContext().authentication)
    verify(chain).doFilter(req, res)
}

@Test
fun `invalid bearer token leaves SecurityContext empty without throwing`() {
    val req = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer garbage") }
    val res = MockHttpServletResponse()
    val chain: FilterChain = mock()

    assertDoesNotThrow { filter.doFilter(req, res, chain) }
    assertNull(SecurityContextHolder.getContext().authentication)
    verify(chain).doFilter(req, res)
}

@Test
fun `non bearer authorization header is ignored`() {
    val token = jwt.sign("u-1", "a@b.com")
    val req = MockHttpServletRequest().apply { addHeader("Authorization", "Basic $token") }
    val res = MockHttpServletResponse()
    val chain: FilterChain = mock()

    filter.doFilter(req, res, chain)
    assertNull(SecurityContextHolder.getContext().authentication)
    verify(chain).doFilter(req, res)
}
```

- [ ] **Step 5: Update `SecurityIntegrationTest` bearer flow**

Add `JsonPath` import:

```kotlin
import com.jayway.jsonpath.JsonPath
```

Rename the unauthenticated test and replace the authenticated project test:

```kotlin
@Test
fun `projects endpoint without bearer token returns 401`() {
    mvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized)
}

@Test
fun `projects endpoint with valid bearer token does not return 401`() {
    val registerResponse = mvc.perform(post("/api/v1/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(registerBody("user@example.com", "password123")))
        .andReturn()
        .response
        .contentAsString
    val token: String = JsonPath.read(registerResponse, "$.token")

    val result = mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer $token")).andReturn()
    assert(result.response.status != 401) {
        "Expected request through auth gate, got 401"
    }
}
```

- [ ] **Step 6: Run narrow backend tests and verify they fail**

Run:

```bash
cd backend && ./gradlew test --tests "*AuthControllerTest" --tests "*JwtAuthenticationFilterTest" --tests "*SecurityIntegrationTest"
```

Expected: failures mention missing `$.token`, unexpected `Set-Cookie`, and bearer-token requests returning 401. If the test code does not compile, fix only the test syntax until it compiles and then fails for the intended behavior.

- [ ] **Step 7: Commit failing backend tests**

```bash
git add backend/src/test/kotlin/com/agentwork/productspecagent/api/AuthControllerTest.kt backend/src/test/kotlin/com/agentwork/productspecagent/auth/JwtAuthenticationFilterTest.kt backend/src/test/kotlin/com/agentwork/productspecagent/api/SecurityIntegrationTest.kt
git commit -m "test(auth): expect bearer token auth"
```

---

### Task 2: Backend Bearer Auth Implementation

**Files:**
- Modify: `backend/src/main/kotlin/com/agentwork/productspecagent/domain/AuthDtos.kt`
- Modify: `backend/src/main/kotlin/com/agentwork/productspecagent/api/AuthController.kt`
- Modify: `backend/src/main/kotlin/com/agentwork/productspecagent/auth/JwtAuthenticationFilter.kt`
- Modify: `backend/src/main/kotlin/com/agentwork/productspecagent/config/AuthProperties.kt`
- Delete: `backend/src/main/kotlin/com/agentwork/productspecagent/auth/AuthCookieService.kt`

- [ ] **Step 1: Add token response DTO**

In `AuthDtos.kt`, keep `AuthMeResponse` unchanged and add:

```kotlin
data class AuthTokenResponse(
    val token: String,
    val userId: String,
    val email: String,
    val admin: Boolean = false,
)
```

- [ ] **Step 2: Stop injecting and using `AuthCookieService`**

In `AuthController.kt`, remove:

```kotlin
import com.agentwork.productspecagent.auth.AuthCookieService
import jakarta.servlet.http.HttpServletResponse
```

Add:

```kotlin
import com.agentwork.productspecagent.domain.AuthTokenResponse
```

Change constructor parameters to:

```kotlin
class AuthController(
    private val users: UserService,
    private val jwt: JwtService,
    private val authProperties: AuthProperties,
)
```

- [ ] **Step 3: Return token JSON from register/login**

Replace the register/login/logout methods in `AuthController.kt` with:

```kotlin
@PostMapping("/register")
fun register(@RequestBody credentials: AuthCredentials): ResponseEntity<AuthTokenResponse> {
    val user = users.register(credentials.email, credentials.password)
    val token = jwt.sign(user.id, user.email)
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(AuthTokenResponse(token, user.id, user.email, isAdmin(user.email)))
}

@PostMapping("/login")
fun login(@RequestBody credentials: AuthCredentials): AuthTokenResponse {
    val user = users.authenticate(credentials.email, credentials.password)
    val token = jwt.sign(user.id, user.email)
    return AuthTokenResponse(token, user.id, user.email, isAdmin(user.email))
}

@PostMapping("/logout")
fun logout(): ResponseEntity<Void> {
    return ResponseEntity.noContent().build()
}
```

Leave `me()` returning `AuthMeResponse`.

- [ ] **Step 4: Read bearer tokens in the JWT filter**

In `JwtAuthenticationFilter.kt`, remove cookie lookup and replace `doFilterInternal` token extraction with:

```kotlin
override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
    val token = request.getHeader("Authorization")
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(" ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    if (token != null) {
        val payload = jwtService.parse(token)
        if (payload != null) {
            val authorities = if (isAdminEmail(payload.email)) {
                listOf(SimpleGrantedAuthority("ROLE_ADMIN"))
            } else {
                emptyList()
            }
            val auth = UsernamePasswordAuthenticationToken(
                payload.userId,
                null,
                authorities
            )
            SecurityContextHolder.getContext().authentication = auth
            request.setAttribute("authEmail", payload.email)
        }
    }
    filterChain.doFilter(request, response)
}
```

- [ ] **Step 5: Remove cookie config**

In `AuthProperties.kt`, remove the `cookie` property and nested `Cookie` data class. The file should contain:

```kotlin
@ConfigurationProperties(prefix = "auth")
data class AuthProperties(
    val jwt: Jwt = Jwt(),
    val adminEmails: List<String> = emptyList(),
) {
    data class Jwt(
        val secret: String = "",
        val expirySeconds: Long = 604800, // 7 days
    )
}
```

- [ ] **Step 6: Delete the unused cookie service**

Delete:

```bash
backend/src/main/kotlin/com/agentwork/productspecagent/auth/AuthCookieService.kt
```

Use `apply_patch` delete or `rm` only for this file.

- [ ] **Step 7: Remove cookie test properties**

In `AuthControllerTest.kt` and `SecurityIntegrationTest.kt`, remove:

```kotlin
registry.add("auth.cookie.secure") { "false" }
```

- [ ] **Step 8: Run backend auth tests**

Run:

```bash
cd backend && ./gradlew test --tests "*AuthControllerTest" --tests "*JwtAuthenticationFilterTest" --tests "*SecurityIntegrationTest"
```

Expected: all selected tests pass.

- [ ] **Step 9: Search for stale backend cookie auth references**

Run:

```bash
rg -n "AuthCookieService|auth\\.cookie|cookie\\.secure|Set-Cookie|session cookie|valid session cookie|request\\.cookies" backend/src/main backend/src/test
```

Expected: no backend auth implementation/test references remain. If references remain in comments or old test names, update them to bearer-token wording.

- [ ] **Step 10: Commit backend implementation**

```bash
git add backend/src/main/kotlin/com/agentwork/productspecagent/domain/AuthDtos.kt backend/src/main/kotlin/com/agentwork/productspecagent/api/AuthController.kt backend/src/main/kotlin/com/agentwork/productspecagent/auth/JwtAuthenticationFilter.kt backend/src/main/kotlin/com/agentwork/productspecagent/config/AuthProperties.kt backend/src/test/kotlin/com/agentwork/productspecagent/api/AuthControllerTest.kt backend/src/test/kotlin/com/agentwork/productspecagent/auth/JwtAuthenticationFilterTest.kt backend/src/test/kotlin/com/agentwork/productspecagent/api/SecurityIntegrationTest.kt
git add -u backend/src/main/kotlin/com/agentwork/productspecagent/auth/AuthCookieService.kt
git commit -m "feat(auth): authenticate with bearer tokens"
```

---

### Task 3: Frontend Token Storage And API Header Tests

**Files:**
- Create: `frontend/src/lib/auth-token.ts`
- Modify: `frontend/src/lib/api.ts`
- Modify: `frontend/e2e/auth-api.spec.ts`

- [ ] **Step 1: Create browser-safe token helper**

Create `frontend/src/lib/auth-token.ts`:

```ts
const AUTH_TOKEN_KEY = "productspecagent.authToken";

function storage(): Storage | null {
  if (typeof window === "undefined") return null;
  return window.sessionStorage;
}

export function getAuthToken(): string | null {
  return storage()?.getItem(AUTH_TOKEN_KEY) ?? null;
}

export function setAuthToken(token: string): void {
  storage()?.setItem(AUTH_TOKEN_KEY, token);
}

export function clearAuthToken(): void {
  storage()?.removeItem(AUTH_TOKEN_KEY);
}
```

- [ ] **Step 2: Add header-injection tests**

In `frontend/e2e/auth-api.spec.ts`, import token helpers:

```ts
import { clearAuthToken, setAuthToken } from "@/lib/auth-token";
```

Add a lightweight `sessionStorage` mock inside the `describe` block:

```ts
  const originalWindow = globalThis.window;

  test.beforeEach(() => {
    const values = new Map<string, string>();
    Object.defineProperty(globalThis, "window", {
      configurable: true,
      value: {
        sessionStorage: {
          getItem: (key: string) => values.get(key) ?? null,
          setItem: (key: string, value: string) => values.set(key, value),
          removeItem: (key: string) => values.delete(key),
        },
      },
    });
  });
```

Update `afterEach`:

```ts
  test.afterEach(() => {
    globalThis.fetch = originalFetch;
    Object.defineProperty(globalThis, "window", { configurable: true, value: originalWindow });
    setUnauthorizedHandler(null);
  });
```

Add this test:

```ts
  test("apiFetch sends bearer token from sessionStorage", async () => {
    const authorizationHeaders: Array<string | null> = [];
    setAuthToken("jwt-123");
    globalThis.fetch = async (_url, init) => {
      const headers = new Headers(init?.headers);
      authorizationHeaders.push(headers.get("Authorization"));
      return Response.json({ ok: true });
    };

    await apiFetch("/api/v1/projects");

    expect(authorizationHeaders).toEqual(["Bearer jwt-123"]);
  });
```

Add this test:

```ts
  test("global unauthorized handler clears token on protected 401", async () => {
    setAuthToken("expired-token");
    globalThis.fetch = async () => new Response(null, { status: 401 });
    setUnauthorizedHandler(() => {
      clearAuthToken();
    });

    await expect(apiFetch("/api/v1/projects")).rejects.toThrow("API error: 401");

    expect(window.sessionStorage.getItem("productspecagent.authToken")).toBeNull();
  });
```

- [ ] **Step 3: Run frontend auth API test and verify it fails**

Run:

```bash
cd frontend && npm run test:e2e -- auth-api.spec.ts
```

Expected: the bearer-token test fails because `apiFetch` does not yet attach `Authorization`.

- [ ] **Step 4: Commit failing frontend tests and token helper**

```bash
git add frontend/src/lib/auth-token.ts frontend/e2e/auth-api.spec.ts
git commit -m "test(auth): expect session storage bearer headers"
```

---

### Task 4: Frontend API And Auth Store Implementation

**Files:**
- Modify: `frontend/src/lib/api.ts`
- Modify: `frontend/src/lib/stores/auth-store.ts`

- [ ] **Step 1: Add token helper imports and response type**

In `frontend/src/lib/api.ts`, add:

```ts
import { getAuthToken } from "@/lib/auth-token";
```

Replace the current auth type section with:

```ts
export interface AuthMe {
  userId: string;
  email: string;
  admin: boolean;
}

export interface AuthTokenResponse extends AuthMe {
  token: string;
}
```

- [ ] **Step 2: Add shared auth header helper**

Near `setUnauthorizedHandler`, add:

```ts
function withAuthHeaders(headers?: HeadersInit): HeadersInit {
  const next = new Headers(headers);
  const token = getAuthToken();
  if (token) {
    next.set("Authorization", `Bearer ${token}`);
  }
  return next;
}
```

- [ ] **Step 3: Update `apiFetch`**

Replace the `fetch` call inside `apiFetch` with:

```ts
  const headers = new Headers(requestOptions.headers);
  if (!headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const res = await fetch(`${API_BASE}${path}`, {
    headers: withAuthHeaders(headers),
    ...requestOptions,
  });
```

Do not include `credentials: "include"`.

- [ ] **Step 4: Update auth endpoint return types**

Change auth functions in `frontend/src/lib/api.ts`:

```ts
export async function authRegister(email: string, password: string): Promise<AuthTokenResponse> {
  return apiFetch<AuthTokenResponse>("/api/v1/auth/register", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });
}

export async function authLogin(email: string, password: string): Promise<AuthTokenResponse> {
  return apiFetch<AuthTokenResponse>("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });
}
```

Keep `authMe(): Promise<AuthMe>`.

- [ ] **Step 5: Replace direct fetch credential usage**

In `frontend/src/lib/api.ts`, replace every direct fetch like:

```ts
fetch(`${API_BASE}${path}`, { credentials: "include" })
```

with:

```ts
fetch(`${API_BASE}${path}`, { headers: withAuthHeaders() })
```

For existing calls with headers, merge them:

```ts
const res = await fetch(`${API_BASE}${path}`, {
  method: "POST",
  headers: withAuthHeaders({ "Content-Type": "application/json" }),
  body: JSON.stringify(payload),
});
```

For `FormData`, do not set `Content-Type`; use:

```ts
const res = await fetch(`${API_BASE}${path}`, {
  method: "POST",
  headers: withAuthHeaders(),
  body: formData,
});
```

After edits, run:

```bash
rg -n "credentials: \"include\"|credentials: 'include'" frontend/src/lib/api.ts
```

Expected: no matches.

- [ ] **Step 6: Update auth store token persistence**

In `frontend/src/lib/stores/auth-store.ts`, change imports:

```ts
import { authLogin, authLogout, authMe, authRegister, type AuthMe } from "@/lib/api";
import { clearAuthToken, getAuthToken, setAuthToken } from "@/lib/auth-token";
```

Replace `initialize`, `login`, `register`, `logout`, and `clear` with:

```ts
  initialize: async () => {
    if (!getAuthToken()) {
      set({ user: null, status: "guest" });
      return;
    }
    try {
      const user = await authMe();
      set({ user, status: "authenticated" });
    } catch {
      clearAuthToken();
      set({ user: null, status: "guest" });
    }
  },

  login: async (email, password) => {
    const { token, ...user } = await authLogin(email, password);
    setAuthToken(token);
    set({ user, status: "authenticated" });
  },

  register: async (email, password) => {
    const { token, ...user } = await authRegister(email, password);
    setAuthToken(token);
    set({ user, status: "authenticated" });
  },

  logout: async () => {
    try {
      await authLogout();
    } finally {
      clearAuthToken();
      set({ user: null, status: "guest" });
    }
  },

  clear: () => {
    clearAuthToken();
    set({ user: null, status: "guest" });
  },
```

- [ ] **Step 7: Run frontend auth API test**

Run:

```bash
cd frontend && npm run test:e2e -- auth-api.spec.ts
```

Expected: `auth-api.spec.ts` passes.

- [ ] **Step 8: Run frontend lint**

Run:

```bash
cd frontend && npm run lint
```

Expected: lint passes. Fix TypeScript/ESLint issues in the files touched by this task.

- [ ] **Step 9: Commit frontend API implementation**

```bash
git add frontend/src/lib/api.ts frontend/src/lib/stores/auth-store.ts frontend/src/lib/auth-token.ts frontend/e2e/auth-api.spec.ts
git commit -m "feat(auth): store bearer token in session storage"
```

---

### Task 5: Client Route Gate And Proxy Removal

**Files:**
- Modify: `frontend/src/components/auth/AuthProvider.tsx`
- Delete: `frontend/src/proxy.ts`
- Modify: `frontend/e2e/auth-api.spec.ts`

- [ ] **Step 1: Update AuthProvider to gate routes client-side**

Replace `frontend/src/components/auth/AuthProvider.tsx` with:

```tsx
"use client";

import { useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useAuthStore } from "@/lib/stores/auth-store";
import { setUnauthorizedHandler } from "@/lib/api";

const PUBLIC_PATHS = ["/", "/login", "/register", "/start-screen"];
const AUTH_PATHS = ["/login", "/register"];

function isPathMatch(pathname: string, paths: string[]) {
  return paths.some((path) => pathname === path || pathname.startsWith(`${path}/`));
}

function loginUrl(pathname: string) {
  const search = typeof window === "undefined" ? "" : window.location.search;
  const next = `${pathname}${search}`;
  return `/login?next=${encodeURIComponent(next)}`;
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const initialize = useAuthStore((s) => s.initialize);
  const clear = useAuthStore((s) => s.clear);
  const status = useAuthStore((s) => s.status);
  const pathname = usePathname();
  const router = useRouter();

  const isPublic = isPathMatch(pathname, PUBLIC_PATHS);
  const isAuthPath = isPathMatch(pathname, AUTH_PATHS);

  useEffect(() => {
    initialize();
  }, [initialize]);

  useEffect(() => {
    setUnauthorizedHandler(() => {
      clear();
      router.replace(loginUrl(pathname));
    });
    return () => setUnauthorizedHandler(null);
  }, [clear, pathname, router]);

  useEffect(() => {
    if (!isPublic && status === "guest") {
      router.replace(loginUrl(pathname));
    }
    if (isAuthPath && status === "authenticated") {
      router.replace("/projects");
    }
  }, [isAuthPath, isPublic, pathname, router, status]);

  if (!isPublic && status !== "authenticated") {
    return (
      <div className="min-h-screen bg-background text-foreground" aria-busy="true" />
    );
  }

  return <>{children}</>;
}
```

- [ ] **Step 2: Delete cookie-based proxy**

Delete:

```bash
frontend/src/proxy.ts
```

The client gate now owns page protection because `sessionStorage` is unavailable to Next.js proxy code.

- [ ] **Step 3: Extend unauthorized-handler test coverage**

Do not add a new React unit-test framework in this feature. Use the existing Playwright-based API test file to verify that unauthorized handling clears the stored token.

Add this assertion to the existing unauthorized-handler test in `auth-api.spec.ts` by making the handler clear the token:

```ts
    setAuthToken("jwt-401");
    setUnauthorizedHandler(() => {
      clearAuthToken();
      unauthorizedRedirects += 1;
    });
```

Add:

```ts
    expect(window.sessionStorage.getItem("productspecagent.authToken")).toBeNull();
```

- [ ] **Step 4: Run frontend checks**

Run:

```bash
cd frontend && npm run lint
cd frontend && npm run test:e2e -- auth-api.spec.ts
```

Expected: lint passes and `auth-api.spec.ts` passes.

- [ ] **Step 5: Search for proxy/cookie assumptions**

Run:

```bash
rg -n "req\\.cookies|cookies\\.has\\(\"session\"\\)|sessionStorage|Set-Cookie|credentials: \"include\"|credentials: 'include'" frontend/src frontend/e2e
```

Expected: `sessionStorage` references exist only in the new token helper/tests; cookie and credentials references should not remain in auth code.

- [ ] **Step 6: Commit route-gate changes**

```bash
git add frontend/src/components/auth/AuthProvider.tsx frontend/e2e/auth-api.spec.ts
git add -u frontend/src/proxy.ts
git commit -m "feat(auth): gate routes from client auth state"
```

---

### Task 6: Documentation And Feature Files

**Files:**
- Create: `docs/features/57-session-storage-bearer-auth.md`
- Modify: `docs/features/00-feature-set-overview.md`
- Modify: `docs/architecture/auth.md`

- [ ] **Step 1: Add feature document**

Create `docs/features/57-session-storage-bearer-auth.md`:

```markdown
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
- Next.js `proxy.ts` übernimmt kein Cookie-Gating mehr.

## Akzeptanzkriterien

1. Login/Register liefern einen Token im JSON und setzen kein Auth-Cookie.
2. API-Requests senden `Authorization: Bearer <token>`.
3. Geschützte Endpoints liefern ohne Token 401.
4. Reload mit Token stellt die Session via `/auth/me` wieder her.
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
```

- [ ] **Step 2: Add feature to overview**

In `docs/features/00-feature-set-overview.md`, add after Feature 56 if present, otherwise after Feature 55:

```markdown
| 57 | Session-Storage Bearer Auth | [57-session-storage-bearer-auth.md](57-session-storage-bearer-auth.md) | Feature 42 | M |
```

- [ ] **Step 3: Rewrite auth architecture summary**

In `docs/architecture/auth.md`, replace cookie-specific flow with this bearer-token flow:

```markdown
## Überblick

Login-Gate vor jeder Aktion via Spring Security + selbst-signiertem JWT. Der JWT wird bei Login/Register im JSON zurückgegeben, im Frontend in `sessionStorage` abgelegt und bei API-Requests als `Authorization: Bearer <token>` gesendet. Eingeloggte User teilen einen gemeinsamen Workspace — keine Mitglieder, keine Rollen, keine Per-User-Projekt-Ownership.

## Flow

1. User registriert sich via `POST /api/v1/auth/register` (`{email, password}`)
2. User loggt sich ein via `POST /api/v1/auth/login` (`{email, password}`)
3. Backend validiert BCrypt-Hash, signiert JWT (HMAC-SHA256, ENV-Secret) und antwortet mit `{ token, userId, email, admin }`
4. Frontend speichert den Token in `sessionStorage`
5. Frontend sendet den Token bei API-Aufrufen als `Authorization: Bearer <token>`
6. Backend validiert den Token im `JwtAuthenticationFilter` und setzt den SecurityContext
7. Logout löscht den Token im Browser; der Backend-Endpoint bleibt ein stateless `204`
```

Update the endpoint table so login/register mention token JSON and logout no longer mentions `Set-Cookie`.

- [ ] **Step 4: Run docs grep**

Run:

```bash
rg -n "httpOnly|Set-Cookie|Cookie|cookie|credentials: 'include'|credentials: \"include\"" docs/features/57-session-storage-bearer-auth.md docs/architecture/auth.md docs/features/00-feature-set-overview.md
```

Expected: any remaining cookie mentions are historical context or explicit tradeoff notes, not current architecture instructions.

- [ ] **Step 5: Commit docs**

```bash
git add docs/features/57-session-storage-bearer-auth.md docs/features/00-feature-set-overview.md docs/architecture/auth.md
git commit -m "docs(auth): document bearer token session storage"
```

---

### Task 7: Final Verification And Done Document

**Files:**
- Create: `docs/features/57-session-storage-bearer-auth-done.md`

- [ ] **Step 1: Run backend auth verification**

Run:

```bash
cd backend && ./gradlew test --tests "*AuthControllerTest" --tests "*JwtAuthenticationFilterTest" --tests "*SecurityIntegrationTest"
```

Expected: selected backend tests pass.

- [ ] **Step 2: Run frontend verification**

Run:

```bash
cd frontend && npm run lint
cd frontend && npm run test:e2e -- auth-api.spec.ts
```

Expected: lint passes and `auth-api.spec.ts` passes.

- [ ] **Step 3: Run stale-reference checks**

Run:

```bash
rg -n "AuthCookieService|auth\\.cookie|request\\.cookies|cookies\\.has\\(\"session\"\\)|credentials: \"include\"|credentials: 'include'|Set-Cookie" backend/src/main backend/src/test frontend/src frontend/e2e
```

Expected: no stale implementation or test references remain. If docs mention cookies as historical context, they should not be included in this implementation grep.

- [ ] **Step 4: Write done document**

Create `docs/features/57-session-storage-bearer-auth-done.md`:

```markdown
# Feature 57 — Session-Storage Bearer Auth Done

**Spec:** [`57-session-storage-bearer-auth.md`](57-session-storage-bearer-auth.md)  
**Design:** [`docs/superpowers/specs/2026-06-15-session-storage-bearer-auth-design.md`](../superpowers/specs/2026-06-15-session-storage-bearer-auth-design.md)  
**Plan:** [`docs/superpowers/plans/2026-06-15-session-storage-bearer-auth.md`](../superpowers/plans/2026-06-15-session-storage-bearer-auth.md)

## Zusammenfassung

Die Authentifizierung wurde von `httpOnly` Cookie-Transport auf `Authorization: Bearer <token>` umgestellt. Login und Registrierung liefern den JWT im JSON zurück. Das Frontend speichert den Token in `sessionStorage`, hängt ihn an API-Requests an und schützt Seiten clientseitig nach Hydration. Das Backend authentifiziert stateless über den Authorization-Header.

## Abweichungen

- Next.js `proxy.ts` schützt Seiten nicht mehr request-seitig, weil `sessionStorage` nur im Browser verfügbar ist.
- Logout invalidiert serverseitig keinen Token; der Token wird clientseitig gelöscht.

## Verifikation

- `cd backend && ./gradlew test --tests "*AuthControllerTest" --tests "*JwtAuthenticationFilterTest" --tests "*SecurityIntegrationTest"`
- `cd frontend && npm run lint`
- `cd frontend && npm run test:e2e -- auth-api.spec.ts`

## Offene Punkte

- Token-Lifetime ist weiterhin die bestehende JWT-Laufzeit.
- Ein späteres Hardening-Feature kann eine kürzere Laufzeit oder zusätzliche XSS-Schutzmaßnahmen definieren.
```

- [ ] **Step 5: Commit done document**

```bash
git add docs/features/57-session-storage-bearer-auth-done.md
git commit -m "docs(auth): mark session storage bearer auth done"
```

- [ ] **Step 6: Final status**

Run:

```bash
git status --short --branch
```

Expected: clean working tree on the feature branch or current `main` with implementation commits ahead of remote.

import { expect, test } from "@playwright/test";
import { apiFetch, authMe, setUnauthorizedHandler } from "@/lib/api";
import { clearAuthToken, setAuthToken } from "@/lib/auth-token";
import { redirectAfterAuth } from "@/lib/auth-navigation";

test.describe("auth API unauthorized handling", () => {
  const originalFetch = globalThis.fetch;
  const originalWindowDescriptor = Object.getOwnPropertyDescriptor(globalThis, "window");
  let sessionStorageItems: Map<string, string>;

  const sessionStorage = {
    get length() {
      return sessionStorageItems.size;
    },
    clear: () => {
      sessionStorageItems.clear();
    },
    getItem: (key: string) => sessionStorageItems.get(key) ?? null,
    key: (index: number) => Array.from(sessionStorageItems.keys())[index] ?? null,
    removeItem: (key: string) => {
      sessionStorageItems.delete(key);
    },
    setItem: (key: string, value: string) => {
      sessionStorageItems.set(key, value);
    },
  } satisfies Storage;

  test.beforeEach(() => {
    sessionStorageItems = new Map();
    Object.defineProperty(globalThis, "window", {
      configurable: true,
      value: { sessionStorage },
    });
  });

  test.afterEach(() => {
    globalThis.fetch = originalFetch;
    setUnauthorizedHandler(null);
    if (originalWindowDescriptor) {
      Object.defineProperty(globalThis, "window", originalWindowDescriptor);
    } else {
      Reflect.deleteProperty(globalThis, "window");
    }
  });

  test("authMe treats 401 as guest state without triggering the global unauthorized redirect", async () => {
    let unauthorizedRedirects = 0;
    globalThis.fetch = async () => new Response(null, { status: 401 });
    setUnauthorizedHandler(() => {
      unauthorizedRedirects += 1;
    });

    await expect(authMe()).rejects.toThrow("API error: 401");

    expect(unauthorizedRedirects).toBe(0);
  });

  test("apiFetch still triggers the global unauthorized handler for protected requests", async () => {
    let unauthorizedRedirects = 0;
    globalThis.fetch = async () => new Response(null, { status: 401 });
    setUnauthorizedHandler(() => {
      unauthorizedRedirects += 1;
    });

    await expect(apiFetch("/api/v1/projects")).rejects.toThrow("API error: 401");

    expect(unauthorizedRedirects).toBe(1);
  });

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

  test("global unauthorized handler clears token on protected 401", async () => {
    setAuthToken("expired-token");
    globalThis.fetch = async () => new Response(null, { status: 401 });
    setUnauthorizedHandler(() => {
      clearAuthToken();
    });

    await expect(apiFetch("/api/v1/projects")).rejects.toThrow("API error: 401");

    expect(window.sessionStorage.getItem("productspecagent.authToken")).toBeNull();
  });

  test("auth redirects use a full document navigation", () => {
    const redirects: string[] = [];

    redirectAfterAuth("/projects", {
      replace: (url) => redirects.push(url),
    });

    expect(redirects).toEqual(["/projects"]);
  });
});

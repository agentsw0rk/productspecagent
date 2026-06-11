import { expect, test } from "@playwright/test";
import { apiFetch, authMe, setUnauthorizedHandler } from "@/lib/api";
import { redirectAfterAuth } from "@/lib/auth-navigation";

test.describe("auth API unauthorized handling", () => {
  const originalFetch = globalThis.fetch;

  test.afterEach(() => {
    globalThis.fetch = originalFetch;
    setUnauthorizedHandler(null);
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

  test("auth redirects use a full document navigation", () => {
    const redirects: string[] = [];

    redirectAfterAuth("/projects", {
      replace: (url) => redirects.push(url),
    });

    expect(redirects).toEqual(["/projects"]);
  });
});

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

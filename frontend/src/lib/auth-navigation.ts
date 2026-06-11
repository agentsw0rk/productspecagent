interface AuthRedirectLocation {
  replace: (url: string) => void;
}

export function redirectAfterAuth(
  target: string,
  locationRef: AuthRedirectLocation = window.location,
) {
  locationRef.replace(target);
}

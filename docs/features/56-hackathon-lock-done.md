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

# Changelog

# 2026-09-07 v1.3 reauth prediction

- Added an automatic reauthentication planner that learns from successful logins and keeps a predicted next reauth time on disk.
- Added a safety-window scheduler so proactive reauth can be shifted into quiet hours, with a default window of 04:00-06:00.
- Added `AUTO_REAUTH_ENABLED=1|0` plus safe-window hour controls to the Docker environment and offline installer.
- Updated the Docker image tag and project version to `v1.3`.

## 2026-07-10 v1.23 recovery fix

- Require a successful keep heartbeat before reporting `LOGIN_SUCCESS`, avoiding false authentication when the campus portal still rejects traffic after a login response.
- Reset the recovery budget after confirmed authentication and clear stale OkHttp connections during session cleanup.
- Add bounded login confirmation settings: `LOGIN_CONFIRMATION_ATTEMPTS` and `LOGIN_CONFIRMATION_INTERVAL_SECONDS`.
- Harden the optional OpenWrt watchdog to inspect container thread/health-file liveness instead of restarting on captive-portal redirects or rebooting the router.
- Use Asia/Shanghai timestamps in the runtime image and persisted log filenames by default.

## 2026-07-07 v1.22 offline rebuild

- Built v1.22 for the portal recovery hardening fix.
- Added `HEARTBEAT_INTERVAL_MAX_SECONDS=240` so server-provided 480 second heartbeat intervals are capped locally.
- Updated offline package image tag and installer defaults to `esurfing-dialer:v1.22`.
## 2026-07-07 portal recovery hardening

- Treat captive-portal detections as a recovery signal instead of immediately overriding a fresh authenticated session.
- Added `PORTAL_AUTH_FRESH_SECONDS` and `PORTAL_REAUTH_COOLDOWN_SECONDS` to prevent login-success/reset storms after the campus network forces re-authentication.
- Increased the default portal detection threshold and health auth window to match the observed heartbeat cadence.
- Reset the client recovery counter after a successful heartbeat so old recoveries do not accumulate during stable operation.

## 2026-07-04 v1.21 offline rebuild

- Built the amd64 offline Docker image from the current source tree instead of reusing the old `dialer.tar` image as a base.
- Updated offline package image tag and installer defaults to `esurfing-dialer:v1.21`.
- Added `Dockerfile.runtime` for release-image builds from the current `shadowJar` output without running Gradle inside Docker.
- Kept `DIALER_BACKEND=auto`, so x86_64 does not force Dynarmic by default.
- Kept runtime logs under `data/logs/` with a default 3-day retention window.

## 2026-06-28 follow-up fix

- Keep heartbeats running while authenticated even when connectivity probes return temporary request errors.
- Debounce portal redirects while already authenticated; re-authentication now requires consecutive portal detections.
- Docker healthcheck now requires `authenticated=true` and a recent login/heartbeat timestamp.
- Added `PORTAL_DETECTION_THRESHOLD` and `HEALTH_AUTH_MAX_AGE_SECONDS` deployment settings.

## Docker fixed build

- Added persistent device identity in `device-state.json` so MAC address and Client ID no longer change on every restart or re-authentication.
- Wrapped the client loop with top-level exception recovery to avoid a dead authentication thread while the JVM stays alive.
- Replaced unsafe heartbeat interval parsing with bounded fallback parsing.
- Added bounded login retry backoff instead of a fixed 10 minute sleep after failed login.
- Reset session state after heartbeat failure threshold is reached, then re-enter network detection and login.
- Added multi-URL captive portal checks with support for 301, 302, 303, 307, and 308 redirects.
- Added `health.json` reporting and Docker `HEALTHCHECK`.
- Added Dockerfile, compose file, entrypoint, healthcheck, `.env.example`, and optional OpenWrt watchdog.
- Fixed Docker run documentation to use `--restart unless-stopped` instead of the invalid `-restart=always`.

## Migration from old image

1. Put this directory on the router or build host.
2. Create `.env` from `.env.example`.
3. Start with `docker compose up -d --build`.
4. Keep `./data` mounted permanently. It contains only runtime state, not the password.
5. To roll back, stop this container and run the old image again. Remove `./data/device-state.json` only if you intentionally want a new client identity.

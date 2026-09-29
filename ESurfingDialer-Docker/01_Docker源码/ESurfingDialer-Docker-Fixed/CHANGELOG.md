# Changelog

## Unreleased — 2026-09-29 Docker beta follow-up

- Preserve the local bounded portal parser, safe diagnostics and failed-cache refresh fixes from September 28.
- Validate expected probe status/body; continue past unexpected HTTP 200 responses instead of declaring connectivity.
- Keep accepted authorization requests independently of network observations, capture their portal/version, and acknowledge only the completed version. Retain failed requests for bounded retries, refresh same-portal cached pages and honor shutdown between stages.
- Encode installer credentials for Compose dotenv, including dollars, quotes, whitespace and backslashes; create new configuration with a restrictive umask and document manual escaping.
- Source changes only. Existing beta2 archives/images are unchanged; regression, Compose runtime and campus verification remain pending.

## 2026-09-26 v1.4.0-beta.2 (local testing only)

- Respect stopped/paused containers; add per-container watchdog locking, pause and cooldown, without docker start/restart operations.
- Separate network completion from success; supervise bounded client stages with a monotonic clock and abnormal exit for stalled JNI.
- Validate health times and deadlines; encode scalar state JSON consistently and atomically replace health/device/planner files.
- Qualify lifetime samples; fix past targets and cross-midnight windows, handle corrupt/future state and clock rollback.
- Bound mounted logs per file/total, replace per-line date/tee with a persistent logger, reduce default health write frequency and include read-only runtime sampling.
- Ship an independent ESurfingDialer-Beta2 amd64 offline package; keep beta1 and formal deployments untouched. LuCI is deferred.

## 2026-09-26 v1.4.0-beta.1 (local testing only)

- Validate authentication XML fields and heartbeat intervals; escape dynamic request fields and bound response sizes.
- Discover portal auth/ticket endpoints through bounded redirects, retaining redirects without initial IP parameters.
- Freeze active session endpoints, IP and device headers; serialize session use/free and clean up invalid initialization.
- Add offline authentication regression checks and an isolated amd64 beta package with ESurfingDialer-Beta.
- Keep Android32 native libraries, UA, persistent identity and recovery/scheduler defaults unchanged. Actual container/campus validation is pending.

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

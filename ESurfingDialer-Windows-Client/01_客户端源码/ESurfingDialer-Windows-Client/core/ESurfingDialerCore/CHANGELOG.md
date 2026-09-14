# Changelog

## Itsuwarii Docker fixed build

Base source: `Itsuwarii/ESurfingDialer` main branch, commit `377be9a388f0a17b9835f6271a75321c20f2a613`.

### Program stability

- Added top-level protection around the client loop in `Client.kt`; unexpected exceptions now reset session state, log the stack, back off, and retry.
- Added bounded heartbeat interval parsing. Invalid or missing `<interval>` no longer kills the client thread.
- Added heartbeat failure counting. After `HEARTBEAT_FAILURE_THRESHOLD`, the client clears the session and forces a fresh authorization.
- Replaced the fixed 10 minute login failure sleep with bounded exponential retry controlled by `LOGIN_RETRY_INITIAL_SECONDS` and `LOGIN_RETRY_MAX_SECONDS`.
- Added `resetSessionState(reason, countAbnormalRecovery)` so normal authorization cleanup does not count as an abnormal recovery.
- Added process exit after too many abnormal recoveries so Docker restart policy can take over instead of leaving a fake-live JVM.

### Device identity

- Added `DeviceIdentityStore.kt` and `/data/device-state.json`.
- MAC address and Client ID are stable across container restarts and re-authentication.
- Added optional overrides: `DIALER_MAC_ADDRESS` and `DIALER_CLIENT_ID`.
- Logs mask MAC and Client ID; passwords are never written to state files.

### Network detection

- Added multi-URL network detection through `NETWORK_CHECK_URLS`.
- Supports 301, 302, 303, 307, and 308 captive-portal redirects.
- Supports multiple user IP and AC IP parameter names, case-insensitively.
- Checks suspicious HTTP 200 response bodies for captive portal parameters.

### Health and Docker

- Added `/data/health.json` with thread, auth, network, login, heartbeat, and error state.
- Added Docker `HEALTHCHECK`.
- Added multi-stage Dockerfile that builds the JAR from source and runs on glibc-based Eclipse Temurin 21 JRE.
- Added `docker-compose.yml`, `.env.example`, `.dockerignore`, `docker/entrypoint.sh`, `docker/healthcheck.sh`, and `scripts/openwrt-watchdog.sh`.
- Preserved one-click Docker deployment with environment variables and host networking.

### Migration

1. Stop and remove the old container.
2. Build this version with `docker compose up -d --build`.
3. Keep `./data` mounted permanently.
4. If the old account is stuck online, wait for the school gateway session to expire or use the optional OpenWrt watchdog to reconnect WAN.
5. To roll back, stop this container and start the old image. Remove `./data/device-state.json` only when intentionally changing device identity.

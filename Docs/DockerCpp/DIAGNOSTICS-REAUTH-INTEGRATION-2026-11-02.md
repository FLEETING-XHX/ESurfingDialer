# Slim diagnostics and reauthentication acceptance build

Version remains v3.0.0. This is a new experimental amd64 test package, not a
formal release. No running router deployment has been changed.

## Implemented diagnostics

OPERATION_FAILED preserves stage (probe/discovery/bootstrap/ticket/login/keep/term),
process-local request attempt, request elapsed milliseconds, curl code, actual
HTTP status, recovery count/delay and cause=unknown. Categories distinguish DNS,
connection, timeout, TLS, send/receive, empty reply, response limits, HTTP status,
decryption and protocol validation. Exception strings are never copied verbatim.
Zero HTTP status means no status was obtained. A zero retry delay does not promise
an immediate retry. A timeout or 5xx response alone does not identify root cause.

```text
OPERATION_FAILED stage=keep code=HTTP_TIMEOUT attempt=42 requestElapsedMs=45000 curl=28 http=0 retryCount=1 retryDelaySeconds=5 cause=unknown
```

First heartbeat is HEARTBEAT_OK; stable successes remain 30-minute summaries.
Failures and first recovery are immediate; NETWORK_STATE logs changes only.
Compatibility HEARTBEAT_FAILED/health errors may remain broad; the companion
OPERATION_FAILED provides the underlying observed category. Attempts reset with
process lifetime; timestamps disambiguate restarts and eventual counter wrap.

File logs are in ./log, bound to /data/logs. Inactive files expire 72 hours after
last modification, checked hourly and subject to quotas. Old volume logs are
preserved but not automatically migrated. No credentials, keys, device IDs,
MAC/IP, full URLs or raw request/response bodies are included in new diagnostics.

Optional router snapshot: `sh collect-host-diagnostics.sh > host-diagnostics.txt`.
It records Docker exit/OOM/restart facts, health freshness, interface link states
and wireless disconnect match counts in the last 200 system log lines. Raw
wireless messages and Docker error text are excluded. Missing evidence, interface
roles and attribution are unknown. UTC snapshot time differs from default UTC+8
application timestamps. The script reads state; it never restarts containers,
reads .env or grants the application Docker socket/privileged access.

## Planned reauthentication

The existing learned-lifetime algorithm and default UTC+8 04:00-06:00 window are
preserved. This is not a daily container restart. REAUTH_PLAN logs next Unix epoch,
estimate, window and persistence status on changes. A failed attempt preserves
the previous successful plan until first heartbeat confirmation. Scheduled and
transport failures do not become qualified lifetime samples.

Real-process tests verify scheduled success, one failed scheduled login and
bounded recovery, disabled scheduling, next-plan persistence and unchanged device
identity. A separate heartbeat failure test confirms retry and recovery metadata.
Existing unit tests cover windows, overdue fallback, restart state, migration,
invalid data, clock rollback and persistence failure. Synthetic clock overrides
exist only in the test fixture. Do not edit live clocks or plan files to force a
test; accelerated local tests do not establish multi-day server acceptance.

## Channels and acceptance

Formal source configuration is v3.0.0, NATIVE_RELEASE_CHANNEL=stable and
restart:unless-stopped. Its compiled program no longer requires the experimental
opt-in. No-opt-in lifecycle verification uses NATIVE_EXPERIMENTAL=0 and synthetic
credentials on an isolated network. No formal image tag is published by this work.

This acceptance package has a unique experimental image tag, explicit opt-in and
restart:no. It retains the previous modulefix test project/container/state volume.
Copy the old .env; the saved opt-in persists and installation preserves it. No
automatic watchdog installation or new scheduled container restart is added.

Local validation: Linux 29 groups (27 pass, 2 default private-input groups skip),
Windows core 9 groups, seven actual-runtime native module corpora with 331 checks
each, TLS/DNS/encoding/timezone checks, actual PID1/health/stop guard and root file
manifest. Host snapshots are checked with synthetic observations; router tool
availability and the new runtime's real authentication/recovery still need field
acceptance. Prior full-image field success does not transfer automatically.
Raw private modules/parameters stay outside the test package. Evidence lives in
artifacts/build/diagnostics-reauth-20261102 and artifacts/build/DockerCpp/v3.0.0-slim-diagnostics.

# Release preparation: logs and recovery

This is a source preparation record, not a new release or field acceptance report.
No deployment ZIP or offline image archive was generated for this change.

## File logs

Compose mounts the deployment directory `./log` at `/data/logs`. The installation
and start scripts run `prepare-log.sh`, which creates a dedicated directory and
sets its owner to UID/GID 10001 and mode 0700 using an isolated local-image helper.
It does not pass credentials, mount application state, or start authentication.
It changes only the directory itself, not existing files recursively. Symlink
directories and regular files named `log` are rejected. If preparation fails,
startup stops. Running Compose directly requires preparing this directory first.

The named state volume remains unchanged. Old logs inside that volume are retained
but hidden by the new mount, not migrated or deleted. Before upgrading, optionally
copy the old container's logs into a separate history directory, while it still
uses its old configuration:

```sh
mkdir -p log-history
docker cp ESurfingDialerNative-v3.0.0-modulefix-test:/data/logs/. ./log-history/
```

Do not copy historical files over active logs. Different extracted deployment
directories have different host log directories even when state volumes match.
Keep the previous deployment directory until rollback is no longer needed.
If LOG_DIR is overridden, its path needs a corresponding writable mount; use the
default for the standard deployment.

Events:

- `HEARTBEAT_OK successes=1`: first confirmed keep; subsequent successes are
  summarized at the next successful keep after 30 minutes.
- `HEARTBEAT_FAILED`: failures are not suppressed.
- `HEARTBEAT_RECOVERED`: first successful keep after failures.
- `NETWORK_STATE online|portal|unreachable`: initial observation and changes only.
  `unreachable` means no usable result from the configured probes, not proof that
  the WAN cable is disconnected. External connectivity is separate from owned
  authentication; `LOGIN_CONFIRMED` remains the authentication confirmation.

Default retention is 3 elapsed days since an inactive file's last modification.
Cleanup runs on startup, date change, rotation and hourly maintenance. A file can
remain until the next cleanup. This is file-based retention, not a per-record
72-hour cutoff. The current active file is protected. Setting 0 retains the
legacy minimum of 24 hours. Defaults also cap a file at 10 MiB and the directory's
managed logs at 50 MiB, so quota pressure may remove older files sooner. Only
recognized date log files/rotations are managed; unrelated files are preserved.
Docker json-file output has a separate size policy, not a three-day policy.

## Field recovery procedure

Run only during a maintenance window, from a LAN-connected computer. Keep Kotlin
stopped during native tests. Do not disconnect LAN, reset device identity, delete
volumes, change passwords, or deliberately send malformed requests to the server.
These tests are still required; local fixtures do not establish router behavior.

Use the actual native container name. The current field test name is
`ESurfingDialerNative-v3.0.0-modulefix-test`. Future releases may use another name.

1. Baseline: confirm `LOGIN_CONFIRMED`, a successful first heartbeat, online
   authentication in health.json and working Internet. Record the current image,
   container name and local device identity without sharing credentials.
2. Container restart: `docker restart ESurfingDialerNative-v3.0.0-modulefix-test`.
   Confirm health timestamps resume, identity is unchanged, and authentication
   is confirmed again when the server requires it. If Internet remains available
   from a previous session, `external_online` can be valid; it is not evidence of
   newly confirmed authentication. Do not force repeated logins just to see a log.
3. Manual stop: stop the native container, wait at least 60 seconds and confirm it
   stays stopped under the current restart:no policy. Start it manually and check
   recovery. Automatic restart behavior belongs to the final configured policy.
4. WAN interruption: disconnect only WAN for 30-60 seconds, retain LAN/SSH, then
   reconnect. Observe network state transitions and bounded retries. Expect a
   recovered heartbeat if the same session survives, or a newly confirmed login
   if it does not. No duplicate authenticators or endless fast retry loop.
5. Router restart (last): reboot from the router UI, reconnect from LAN, inspect
   container state and start manually if restart:no is still configured. Confirm
   identity persistence, fresh health output and recovery. This interrupts every
   service on the router and is not a test of automatic startup unless the final
   startup policy explicitly enables it.

After a restart allow two minutes; after WAN restore allow at least the advertised
next heartbeat interval plus one minute (currently 240+60 seconds). Persistent
backoff/paused, stale health or a crash is a failure. Record logs before reverting.
An external_online state without current authentication needs separate assessment.

```sh
docker logs --tail 100 ESurfingDialerNative-v3.0.0-modulefix-test
docker exec ESurfingDialerNative-v3.0.0-modulefix-test cat /data/health.json
```

If recovery fails, stop native first and restore Kotlin:

```sh
docker stop ESurfingDialerNative-v3.0.0-modulefix-test
docker start ESurfingDialer-v2.4
```

If native fails to stop, resolve that before starting Kotlin. Do not restart the
Docker daemon as a first test: it affects unrelated containers. A server-initiated
heartbeat rejection needs an observed event or a separate controlled test; a WAN
interruption alone does not prove handling of server protocol rejection.

## Field observations: 2026-11-02 (UTC+8)

User-supplied screenshot of the existing sm4-response-fix field image:

- Initial authentication confirmed at 15:28:41; periodic successful heartbeat
  summaries continue through 20:09:36, with one failure/recovery pair shown.
- At 19:37:24: HEARTBEAT_FAILED HEARTBEAT_TRANSPORT_ERROR.
- At 19:37:29: HEARTBEAT_RECOVERED failures=1, nextRetry=240s. This establishes
  successful recovery five seconds after the failure was logged, not the duration
  of any preceding failed HTTP request or a proven five-second Internet outage.
- The current implementation labels non-protocol heartbeat exceptions broadly
  as HEARTBEAT_TRANSPORT_ERROR. The screenshot cannot identify DNS, timeout,
  connection, HTTP status, or other underlying causes. No root cause is assigned.
- At 20:38:02: TERM_REQUEST_SENT_UNCONFIRMED and NATIVE_STOPPED, followed by
  bootstrap receipt at 20:38:04 and LOGIN_CONFIRMED / STATE online at 20:38:12.
  User explicitly confirms that after manual container restart the container
  started normally and Internet connectivity was restored. Container startup,
  authentication and Internet recovery after restart therefore pass this field
  observation. Identity equality and fresh health
  snapshots were not independently supplied. The term log is not logout proof.
- In a subsequent user report on 2026-11-02, all four recovery checklist items
  were confirmed successful: container restart (identity/health checks included
  in the selected checklist), manual stop without spontaneous restart followed
  by manual start, 30-60 second WAN interruption/restoration, and router reboot
  with configuration/identity persistence and recovery. Mark these four items
  PASS (user-reported field results). No additional raw health snapshots, test
  timestamps or logs were supplied with that confirmation; do not invent them.
  The earlier identity/health evidence gap above describes the screenshot alone.
- These results apply to the existing deployed sm4-response-fix test image and
  its tested setup. They do not establish automatic startup under a different
  restart policy, server-initiated protocol rejection handling, other CPU/module
  profiles, or long-term stability. No formal release is declared by this record.
- The screenshot uses HEARTBEAT_SUMMARY from the existing deployed image; the
  source-preparation changes above have not been packaged or deployed.

## Integration status

Diagnostics, reauthentication verification, and separate stable/test startup
configuration are now implemented in source and locally checked. See
DIAGNOSTICS-REAUTH-INTEGRATION-2026-11-02.md. The new combined acceptance package
still requires router validation; no formal release is declared.

## Formal release identity and startup policy

User specifies v3.0.0 for the formal release (2026-11-02). Keep test image tags,
artifact names and experimental channel labels distinct from the formal images.
The existing test-package opt-in defaults to NATIVE_EXPERIMENTAL=0 and remains
explicit for the current test work. Saving 1 in an existing .env persists across
starts; installation must not overwrite it. Stable builds now omit the experimental
opt-in requirement. This does not relabel the unaccepted candidate as
production-ready.

User-approved formal Docker restart policy: unless-stopped, respecting manual stop
while permitting recovery/startup when the operator intends the service to run.
Test packages retain restart:no for controlled acceptance and rollback. Do not
silently alter the running router's policy. Docker restart policy is separate
from internal heartbeat retries and planned reauthentication; no daily container
restart is introduced. Formal watchdog configuration must match the selected
policy and remain separately enabled by the operator.

## Failure diagnostics implementation requirements

User requirement confirmed on 2026-11-02: operators must be able to investigate
failures from logs without exposing credentials. The following checklist is
implemented in source and locally validated. It is not present in the older
deployed field image until that image is replaced. Router evidence availability
and acceptance of the new combined build remain field work.

Application diagnostics:

- Preserve the underlying bounded error category through transport, session and
  heartbeat handling. Separate DNS resolution, connection establishment, timeout,
  TLS verification/handshake, send/receive, HTTP status, empty/oversized response,
  decryption and protocol validation errors. Keep an explicit unknown category.
- Include the operation stage (discovery/bootstrap/ticket/login/keep/term), a
  locally generated attempt identifier, bounded elapsed time, retry count/delay,
  and HTTP status when actually received. Do not invent a status for failed
  transport. Record stable numeric curl codes where useful, not raw error text.
- Record failures promptly and recovery explicitly. Retain first-heartbeat
  success, 30-minute success summaries and network state changes; do not return
  to verbose per-request success logs.
- Distinguish observations from causes: an HTTP 5xx response is an observed
  endpoint/intermediary response, not proof of a particular server outage;
  timeout is not proof of disconnected WAN. State cause=unknown when evidence
  cannot identify a cause.

Host diagnostics:

- Provide a separate, optional router-side diagnostic path for interface/link
  state, relevant wireless events and Docker container state, exit code,
  OOMKilled, restart count, and health freshness. Indicate when evidence is
  unavailable or stale. Correlate timestamps with application attempts.
- Do not grant the application Docker socket access or privileged mode merely
  to obtain diagnostics. Do not assume that every router uses the same WAN or
  WLAN interface name. Client Wi-Fi disconnection cannot be inferred solely
  from authentication-container probes.
- Manual stop, normal shutdown, crash, externally killed process and OOM must
  remain distinct where host evidence permits; an absent final application log
  alone does not identify which occurred.

Privacy and acceptance:

- No passwords, tickets, keys, raw request/response bodies, full URLs, real
  Client-ID/MAC, or unfiltered exception strings in normal logs. Diagnostic
  collection must not silently enable raw authentication capture.
- Locally test each supported failure category with synthetic inputs/endpoints,
  its retry/recovery record, and absence of sensitive data. Test the optional
  host collector against supported router environments, including missing tools
  and unavailable permissions. Keep application and host evidence separate.
- Review this checklist and its validation evidence before declaring a formal
  release. Existing broad HEARTBEAT_TRANSPORT_ERROR logs cannot retrospectively
  provide the missing underlying cause.

## Dependency inventory details

The existing runtime includes no compiler, CMake, Ninja, Java or Unidbg. Those are
development dependencies. The native executable is about 643 kB in the field image.
The local inventory found approximately 90 MiB in /usr/lib, 3.9 MiB in timezone
data, 2.2 MiB in documentation and 4.9 MiB in package metadata (directory totals
overlap other accounting; they are not compressed archive sizes).

Keep libcurl, libxml2, liblzma, OpenSSL, libstdc++, libc and their dynamic dependency
closure. In particular, ICU is required by the installed libxml2, and LDAP,
Kerberos, SSH/RTMP and other libraries are linked by the installed libcurl.
Deleting them because the application does not use those protocols would break
dynamic loading. Keep jq and POSIX shell/core utilities for health checks and
permission preparation, CA certificates for TLS, and timezone data while arbitrary
TZ values are supported. Retain license notices and package metadata for audit.

No runtime library has been deleted without a dependency proof. The useful next
size reduction is a separately validated minimal dependency build (e.g. narrower
libcurl and libxml2 features), rather than removing linked shared objects or
silently switching the known working glibc runtime to musl. Measure compressed
download size and unpacked disk footprint separately. This change does not claim
an image size reduction.


## Daily container restart - 2026-11-04 source change

This section supersedes the earlier planned-reauthentication behavior for the
current source only. Previously delivered images and ZIP files are unchanged.
No new deployment/test package is generated for this change.

- Replace lifetime prediction and scheduled in-process reauthentication with one
  clean process exit per UTC+8 calendar day, eligible only in [04:00, 06:00).
  There is no fixed 04:01 deadline; the first eligible check triggers it.
- Retain Docker restart policy `unless-stopped`. Docker restarts the container
  after this exit and the normal startup flow authenticates. No Docker socket,
  privilege escalation, host cron job or independent starter is added.
- Persist the daily claim in `daily-restart.json` before exiting. The next process
  will not repeat the scheduled exit on the same day. Missed windows are skipped.
  Allow 15 seconds of initial uptime before relying on Docker's restart policy.
- Manual stops remain stopped. Existing fault recovery is separate and is not
  constrained to the planned-maintenance window.
- Expose `dailyRestart*` fields in health.json and log `RESTART_PLAN`,
  `SCHEDULED_RESTART_TRIGGERED`, and persistence failures. Remove obsolete
  lifetime-estimate health fields and do not change the old `reauth-plan.json`.
- New configuration uses `DAILY_RESTART_ENABLED` and
  `DAILY_RESTART_SAFE_WINDOW_START_HOUR` / `END_HOUR`. Old AUTO_REAUTH names remain
  fallback aliases only; a new name overrides its old counterpart.
- Future generated experimental Compose files also retain `unless-stopped`,
  since `restart: no` cannot execute the daily restart. Existing packages are not
  rewritten. Experimental opt-in checks remain intact.

The allowed window governs the planned restart request. Docker scheduling and
server authentication latency may delay completion. This does not establish a
memory leak or prove campus availability after a newly built image is deployed.

Validation for this source change: Windows core 9/9; Linux CTest 27 passed and two
private module corpora skipped; deployment-script fixtures 33 checks; synthetic
legacy-state migration passed. An actual local Docker container (non-root PID 1,
read-only root, network none, synthetic clock) verified normal-exit restart,
persisted same-day deduplication, manual-stop suppression and manual-start
behavior in eight checks. Dedicated test containers/volumes were removed.
This does not constitute router/campus acceptance. The previous acceptance ZIP
still has SHA256 c599d12935d721b45d1850e7700a1fdc85c1b699c478172cce10dc635429e2db.


## Chinese logs and combined amd64 acceptance package - 2026-11-06

Runtime stdout and file logs now use UTF-8 Chinese descriptions with retained
English event/error identifiers. health.json stays machine-readable with English
keys. The installation directory's `log/` bind is the direct WinSCP entry; no
export is needed. Default retention now keeps today and the previous two local
calendar dates, superseding the earlier 72-hours-since-mtime rule. Date changes
and hourly maintenance remove older owned log files; size limits may remove them
sooner. Docker json-file history retains its separate capacity-based rotation.

Logs distinguish HTTP/transport, protocol/session, module/cipher compatibility,
core/reporting failures, requested shutdown, and an unclean previous run. The
existing sysfs interface view supplies initial link states and changes every five
seconds, including wireless type only when identified by sysfs. No WAN/LAN role
or causal attribution is invented. SIGKILL/OOM/host failure cannot be reported by
a dead process: collect-host-diagnostics.sh provides current Docker exit/OOM facts,
and exit code 137 alone is explicitly not treated as proof of OOM.

Combined validation: Windows core 9/9, Linux CTest 27 passed (two private tests
skipped by default), seven private corpora each 331 checks in the actual minimal
runtime with network none, platform/TLS/encoding/timezone checks, real PID1
lifecycle and Docker daily-restart/manual-stop checks. Synthetic loopback and
clock tests are not campus acceptance. Isolated helper containers were cleaned.

Delivered `ESurfingDialer-DockerCpp-v3.0.0-zh-logs-daily-amd64-test.zip`, 29,952,411
bytes, SHA256 8f53022dc96f5a6c60311ba77e922726286c4dbc98dee4c0ec2a6b26551c0a53.
The previous package is unchanged. New package retains the modulefix-test
container name and state-volume identity, experimental opt-in and version 3.0.0;
restart policy is now unless-stopped. Keep the old image and state for rollback.


## Field acceptance update - 2026-11-06 (user reported)

Applies to the Chinese-log/daily-restart amd64 acceptance package. These are
user-reported results, not independently inspected router traces.

| Check | Field status | Evidence |
| --- | --- | --- |
| New package startup and device identity preservation | PASS, user reported | User confirmed no issue. |
| Direct Docker/WinSCP log viewing | PASS, user reported | User confirmed no issue. |
| Manual container stop and subsequent start | PASS, user reported | User confirmed no issue. |
| Scheduled restart within UTC+8 04:00-06:00, only once per day | PASS, user reported | User confirmed daily restart works; router traces/timestamps not independently inspected. |
| Manual stop spanning the entire maintenance window | PENDING - must retain | Stop the container before 04:00 and leave it stopped through 06:00. It must remain stopped; start manually afterward. Do not change the router clock. |
| Startup after a missed maintenance window | PENDING - must retain | Start the container after 06:00 when it was not running in the window. It must authenticate normally and must not perform a late scheduled restart. Do not change the router clock. |
| New-package WAN outage and restoration | PASS, user reported | User reports recovery works; cause labels remain insufficiently specific. |
| New-package router reboot recovery | PASS, user reported | User reports other applicable amd64 field checks normal; no detailed traces supplied. Daily scheduled-restart boundaries remain pending. |
| Calendar-date log cleanup | PENDING | Confirm only today and previous two dates remain after date rollover/maintenance. |
| Long-running resource and network stability | PENDING | Record comparable uptime samples and pre/post daily restart values. |
| ARM64 and ARMv7 current combined package | NOT FIELD VALIDATED | amd64 success does not prove other architectures. |

Native OpenWrt/IPK deployment remains a feasibility discussion, not an authorized
implementation in this update. It can reuse the C++ core but requires target
SDK/ABI builds, dependency accounting, service/procd integration and smaller log
budgets. A LuCI page is an optional separate component. Earlier suggested package
size and memory figures are design budgets, not measured native build results.


### Follow-up field feedback and WAN diagnostic gap - 2026-11-06

User reports applicable amd64 field tests normal except ongoing multi-day log
cleanup and daily scheduled restart. Preserve both explicit daily-window boundary
checks as pending; this statement does not establish ARM support or a completed
multi-day resource trend. Recovery succeeds, but current cause reporting needs
improvement.

Source review: NETWORK_STATE unreachable means the configured reachability probes
failed to produce a recognized online/portal result. It is not a physical WAN
failure code. Probe failures can include DNS, connect/timeout, HTTP errors or an
unexpected body. The current probe aggregates only the last error. LinkMonitor
reports carrier/operstate changes, but does not identify which interface is WAN.

Pending enhancement: map WAN to its actual logical and physical interfaces and
combine that evidence with reachability diagnostics. Report a confirmed lack of
carrier only as a link disconnection on the identified interface. Report DNS,
HTTP/probe and authentication/module faults separately. Without WAN evidence,
keep the cause unconfirmed; do not relabel every probe failure as suspected WAN
failure. Preserve per-probe evidence or a bounded summary rather than attributing
all failures to the last probe. No runtime source or delivery artifact changed
for this diagnostic discussion.


## Next-build source: WAN evidence and concise operator console - 2026-11-06

This section supersedes the identical-stdout/file display for the next build only.
The delivered Chinese-log ZIP is unchanged and no replacement package is created.

- Docker/OpenWrt console: concise Chinese status, maintenance and failure/recovery
  summaries with useful error identifiers. Suppress the full interface inventory,
  module hashes, request counters and duplicate successful state transitions.
- ./log: preserve full bounded diagnostic records, including all observed interface
  changes, request timing, numeric curl/HTTP results, plan state and probe details.
  Existing three-calendar-date retention and machine-readable health.json remain.
- WAN_INTERFACE identifies a kernel logical device; WAN_PHYSICAL_INTERFACE identifies
  its physical device. Explicit identity is needed for definite WAN labels. The
  default IPv4 route supplies only an exit candidate, never proof of a WAN role.
  IPv6/policy routing is not inferred. Physical and logical link evidence are kept
  separate; a missing PPP interface with physical carrier up is not cable failure.
- Fresh interface observations (maximum ten seconds old) are combined with bounded
  per-probe failures. Preserve up to eight ordered failures without URLs; report
  attempted count and summarize repeated identical batches. Distinguish absent
  IPv4 default route, DNS/transport, HTTP/probe-response and uplink link failures.
  HTTP body mismatch alone is not proof of Internet outage. Keep unproven upstream
  failures explicitly suspected, not confirmed WAN faults.
- Existing daily-window constraints remain: manual stop does not start the service,
  and a startup after 06:00 never catches up that day's maintenance. Added a real
  process late-start mode and explicit Docker stop-across-window/missed-window
  verification. Router field confirmation of daily maintenance remains pending.


Next-build verification completed: Windows 9/9; Linux 28 passed with two private
corpora intentionally skipped; deployment-script fixtures 33 checks. Tests cover
physical/logical WAN separation, route-candidate attribution, stale evidence,
carrier-up without reachability, ordered multiple probe failures, full file versus
concise console output, secret redaction, and late startup after 06:00. A real local
Docker test with a synthetic clock passed nine checks, including manual stop across
the next-day window and startup afterward without catch-up. No host/router clock
was changed. Test containers and their dedicated volumes were cleaned. Both
previously delivered ZIP files are unchanged; field maintenance checks stay pending.


## Daily restart field result - 2026-11-06 (user reported)

User confirms the deployed Chinese-log/daily-restart amd64 package's daily restart
works. The remaining main acceptance items are three-calendar-date file-log cleanup
and sustained operation/resource trends. Daily maintenance is now user-reported
PASS, not an inference from local clock fixtures.

The two special boundary scenarios (manual stop across the entire window and
startup after a missed window) remain separately tracked: local Docker tests
passed, but explicit router-field confirmation of those exact scenarios was not
provided. Do not interpret a normal scheduled restart as proof of both boundaries.

The newer WAN attribution and concise-console source changes have not been
packaged/deployed; this field result applies to the existing delivered package.

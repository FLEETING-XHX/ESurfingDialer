# ESurfingDialer Docker C++ v3.0.0

Native C++20 authentication core. Formal-channel configuration is prepared; this
repository state is not itself a published release. Use the supplied experimental
acceptance package until the combined changes have passed field acceptance.

## Release channels

- Formal channel: v3.0.0, NATIVE_RELEASE_CHANNEL=stable at build time, no
  NATIVE_EXPERIMENTAL opt-in requirement, restart policy unless-stopped.
- Test packages: distinct image suffix and experimental label, explicit
  NATIVE_EXPERIMENTAL=1 in .env, restart policy unless-stopped. The saved setting persists;
  starting a container does not reset it. Start scripts never source/eval .env.
- Source Compose selects the stable build channel. The test packager explicitly
  generates the guarded experimental configuration instead. No running router
  configuration is changed by modifying these source files.

The Dockerfile offers runtime-full and runtime-slim targets. The default remains
the full runtime; the acceptance package selects the minimal glibc target. The
first matched comparison reduced unpacked rootfs from 139.4 to 75.4 MiB while
retaining certificates, timezone data, character conversion, health tools and
dynamic dependency closure. See ../../Docs/DockerCpp/MINIMAL-GLIBC-RUNTIME.md.
The updated runtime-slim candidate rebuilds the patched Debian XML/network
libraries without ICU or unused protocols, and narrows iconv modules and tools.
runtime-slim-control retains the previous complete minimal root. Compatibility
boundaries and validation are in ../../Docs/DockerCpp/LEAN-RUNTIME-2026-11-06.md.
The program has no JVM/Unidbg runtime. The 30 KiB ARM loader file is verified data,
not executed code; see profiles/README.md. Architecture support is not established
by an amd64 result: ARM builds/emulation and physical validation are separate.

## State and logs

Device identity, daily restart state and historical reauthentication data remain in the named state
volume. Compose binds ./log on the host to /data/logs in the container, visible
in WinSCP. Generated install/start scripts prepare the dedicated directory for
UID/GID 10001 with mode 0700; old files are not changed recursively. Direct
Compose users must prepare the directory and permissions before create/start.
Historical logs in an old volume are retained but are not automatically migrated.

File logs retain full bounded diagnostic events; stdout uses concise Chinese
operator summaries and hides interface inventories, hashes and request counters.
First heartbeat success is
HEARTBEAT_OK, followed by 30-minute summaries. Failures and first recovery are
logged immediately. NETWORK_STATE records initial observation and changes only.
OPERATION_FAILED preserves stage, process-local attempt number, request elapsed
milliseconds, numeric curl/HTTP result and recovery count/delay. Zero HTTP status
means no status was obtained; cause=unknown avoids claiming a physical WAN or
server cause solely from a timeout. Arbitrary exception text is not logged.

Defaults: LOG_RETENTION_DAYS=3 (today and the previous two local calendar dates,
checked hourly and on date changes), LOG_FILE_MAX_BYTES=10485760, LOG_TOTAL_MAX_BYTES=52428800,
LOG_ROTATED_FILES=2. Setting days=0 keeps the current calendar date. The active file
is protected and quotas may remove older files earlier. Only exact owned date
log/rotation names are managed. Docker json-file output has separate size limits.
Do not share the log directory with Kotlin. Changing LOG_DIR also requires a
matching writable mount. Runtime root remains read-only and capabilities dropped.

Optional collect-host-diagnostics.sh runs on the router, not in the application.
It reads container exit/OOM/restart facts, health freshness and interface link
states, plus wireless disconnect match counts. It does not dump .env, addresses,
SSID/MAC, raw wireless messages or Docker error strings. Interface roles and root
cause remain unknown without corroborating evidence; missing tools are reported.
Do not give the application a Docker socket or privileged access for diagnosis.

## Daily container restart

DAILY_RESTART_ENABLED defaults to 1. At the first eligible check inside the UTC+8
window (04:00 inclusive to 06:00 exclusive by default), the program records today's
claim in /data/daily-restart.json, cleans up its session and exits normally. Docker's
unless-stopped policy starts a new process, which performs normal authentication.
This replaces the learned 48-hour reauthentication schedule. There is no fixed
04:01 trigger and no second daily in-process reauthentication schedule.

DAILY_RESTART_SAFE_WINDOW_START_HOUR and DAILY_RESTART_SAFE_WINDOW_END_HOUR configure
a same-day window; frequency is always once per UTC+8 calendar day. A missed window
is skipped until the next day, with no overdue execution outside the window.
A process starting inside the window waits at least 15 seconds before becoming
eligible, so Docker can observe startup before the program relies on its policy.
A completed daily claim survives process/container/router restarts. Invalid or
unwritable restart state blocks planned restarts and is logged; normal networking
and fault recovery continue. Manually stopped containers stay stopped because
there is no external timer to start them. Docker must be available to restart the
container; running the executable directly only exits it at the scheduled time.

RESTART_PLAN records the next eligible epoch, window, previous trigger and
persistence status. SCHEDULED_RESTART_TRIGGERED records a claimed planned exit;
subsequent LOGIN_CONFIRMED and HEARTBEAT_OK verify authentication after startup.
The trigger is constrained to the window; Docker startup and server response
latency cannot be guaranteed by the application. Fault exits, operator actions
and Docker/router recovery are separate from this daily schedule.

Existing AUTO_REAUTH_ENABLED and AUTO_REAUTH_SAFE_WINDOW_* values are accepted as
fallback aliases when their corresponding DAILY_RESTART_* names are absent. They
now control daily restarts, not lifetime learning. Old /data/reauth-plan.json is
retained but neither read nor rewritten. New settings take precedence.
Previously delivered test packages are unchanged and retain their old behavior.

## Developer checks

Linux dependencies: CMake, C++20, libcurl, libxml2, LZMA, OpenSSL Crypto/SSL,
CA certificates, timezone data and jq. All time ABIs must be at least 64 bit.
Windows defaults to core-only tests and does not validate a Linux runtime.

```sh
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build
ctest --test-dir build --output-on-failure
```

Tests use synthetic accounts, isolated loopback services and a separate fixture
executable. Synthetic clock overrides exist only in that fixture, never in the
production executable. Private module corpora are explicitly skipped unless
supplied, and raw module/key/vector material is not included in deployment files.
Offline tests and prior full-runtime field observations do not establish field
acceptance for a new minimal runtime, other CPU architectures or unknown modules.
Keep a working image, original configuration and device state for rollback.

## Chinese runtime diagnostics

Docker stdout and log files use UTF-8 Chinese descriptions with stable English
identifiers in brackets. JSON field names and ASCII file names are unchanged.
Network interfaces are observed through the existing read-only sysfs view every
five seconds, with initial state and changes logged. Wireless type is reported
only when sysfs identifies it; interface roles (WAN/LAN/uplink) are not inferred.
A link change is evidence, not proof it caused authentication loss. Unavailable
interface metadata is reported without claiming a physical disconnection.

Fault records distinguish transport/HTTP, authentication protocol, module/cipher
compatibility and core runtime problems. An unclean previous run is noted at
startup. A process killed by Docker or the host cannot log its own final cause:
run `sh collect-host-diagnostics.sh` for current Docker OOM/exit facts. Exit code
137 alone does not establish OOM. No Docker socket or privileged access is added.
The daily restart window and manual-stop behavior remain as documented above.

## WAN evidence and operator summaries (next build)

The console shows concise success/recovery/failure messages. Detailed UTF-8 logs
remain directly accessible in ./log for AI or diagnostic tools; health.json stays
unchanged. Normal inventory, module hashes and request counters are file-only.

WAN_INTERFACE optionally identifies the actual kernel logical uplink device;
WAN_PHYSICAL_INTERFACE optionally identifies its physical device. They are not
UCI section names. For example, a PPPoE deployment may use pppoe-wan and a separate
Ethernet/VLAN device; names must be checked on the target router. Leave both empty
for IPv4 default-route candidate detection. Candidates, cached routes, bridges,
VPNs and policy routing must not be treated as confirmed physical WAN identity.
IPv6-only uplinks require explicit configuration; IPv6/policy routes are not
inferred from /proc/net/route. No host Docker socket or extra privilege is needed.

On failed reachability checks, fresh interface observations are combined with up
to eight ordered, address-free probe failures. Logical disconnect, physical lack
of carrier, missing interfaces, absent IPv4 default route, DNS/transport errors
and unexpected HTTP responses are reported separately. Identical diagnostic
batches are suppressed until their evidence changes; file logs retain each
reported probe's timing and numeric transport/HTTP results. A carrier-up signal
is not proof of Internet access. Physical-down wording requires an explicitly
identified physical interface; it is not proof a cable is defective.

Daily maintenance still never catches up outside its window. Manual stops remain
stopped; no external timer starts the container. These existing constraints are
covered by additional late-start and stop-across-window regression checks. The
previously delivered ZIP is unchanged; this work is for the next build.

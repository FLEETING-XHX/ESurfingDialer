# Docker C++ v3.0.0 local stable release

Built on 2026-11-09. All packages use stable channel; no experimental opt-in.

| Package | ZIP MiB | Expanded package directory MiB |
| --- | ---: | ---: |
| amd64 | 8.22 | 8.21 |
| arm64 | 7.79 | 7.78 |
| armv7 | 6.76 | 6.76 |
| auto | 22.69 | 22.69 |

Runtime rootfs: amd64 24.73 MiB, arm64 25.27 MiB, armv7 19.3 MiB.

Each architecture passed 29 regression tests; two optional private tests were skipped in CTest and nine private corpora were then tested separately in each actual runtime filesystem. Each corpus passed 331 checks. Coverage includes logs, heartbeat recovery, log failure, planned exit, identity, state sync failures, DNS, twelve encoding conversions, XML, TLS, health, watchdog and clean nonroot PID1 exit.

ARM testing used QEMU, not physical hardware or campus authentication. The user reported amd64 test-build field acceptance; stable builds were checked offline afterward.

ICU, unused network libraries, Bash and ldd are absent. CA trust, Chinese/Unicode conversion and timezone data remain. Build tools and source archives stay in builder stages. Package entry names are ASCII and documents are English.

The auto bundle imports only the detected architecture. Engine, caches, logs and old images are excluded from size metrics.

Stop the previous native container, extract into a new directory, copy the old .env or create it from .env.example, then run sh install.sh and sh start.sh. Keep existing volumes. The previous Compose project/container identity is deliberately retained to preserve state. Do not use docker compose down -v. Detailed UTF-8 logs remain in ./log, keeping today and the previous two calendar dates.

For source builds select --target runtime-slim --build-arg NATIVE_RELEASE_CHANNEL=stable. The default Dockerfile target remains the full-runtime fallback.

Evidence: artifacts/build/release-20261109/release-validation.json and per-architecture logs. No remote push or public publishing was performed.

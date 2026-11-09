# Minimal glibc runtime candidate (2026-11-02)

An opt-in amd64 candidate is implemented and locally validated. It is not a
formal release or a router deployment. The default Dockerfile target still uses
the established full Debian runtime. No ZIP or offline image archive was made.

## Measured sizes

The two controls use the same compiled program and runtime library source stage.
Docker container inspect --size reports their root filesystems as:

| Metric | Full control | Minimal candidate |
| --- | ---: | ---: |
| Root filesystem bytes | 146198528 | 79036416 |
| Root filesystem MiB | 139.43 | 75.38 |
| Docker image inspect Size bytes | 50987832 | 30099365 |

Root filesystem size decreased by about 45.9%. Image inspect Size is a different
Docker storage metric, not a measured ZIP size. These numbers are for matched
local images, not a claim that every Docker UI reports the same size. Shared
layers, build cache, test images and volumes affect total host disk consumption;
this work does not claim to have reclaimed that amount on the development host.
The earlier 67.18 MiB trial omitted dynamic character conversion modules and is
superseded by the final 75.38 MiB candidate. Disk reduction does not establish a
matching RAM reduction; the same application/libraries are still loaded.

## Construction and retained behavior

`docker/collect-runtime.sh` assembles a fresh root from the full runtime. It
copies the unchanged application and loader data, the dynamic library dependency
closure of the application and selected tools, NSS modules, glibc character conversion modules/tables and OpenSSL providers,
CA trust anchors, OpenSSL configuration, all timezone data, identity/NSS settings,
and copyright notices. It retains shell/jq and the utilities required by health
checks, the watchdog guard and log permission setup, plus bounded diagnostics.
The final runtime-slim stage starts from scratch; it does not include apt/dpkg,
the rest of the distribution, build tools, development files, or general manuals.

glibc, libcurl, libxml2, OpenSSL, LZMA, C++ support and their required ICU/other
dependencies remain unchanged. No TLS verification was disabled. CA trust is
server identity verification, unrelated to ownership of this project; the main
CA bundle is 222073 bytes (about 217 KiB). Test certificates are synthetic and
are generated/removed by the test process, not installed in the runtime image.

The runtime contains a source-distribution package inventory with an explicit
note that it is not an installed-package database, copyright files and a SHA-256
file manifest. It has no package manager: security updates require rebuilding
from updated source stages, not installing packages in a running container.
Future runtime tools or libraries need explicit additions to the collection and
tests; do not assume a full general-purpose Linux environment remains present.

## Validation

- Builder CTest: 22 passed, 2 private-input groups explicitly skipped by default.
- The new runtime-platform-regression verifies localhost resolution, three time
  zones, GB18030-to-UTF8 conversion, loading the system CA bundle, trusted synthetic HTTPS, rejection of an
  untrusted certificate and rejection of a hostname mismatch.
- Test-MinimalRuntime.ps1 runs the compiled checks inside both actual runtime
  images. Docker DNS resolution is checked on a dedicated internal-only network.
  Both pass platform checks, seven loopback HTTP scenarios, 61 SM4 checks and
  seven private session corpora, each with 331 checks. Corpora cover SM4 responses
  with/without terminal NUL, ID/parameter rotations and the legacy TEA profile.
  This is synthetic/offline protocol evidence, not server authentication.
- The minimal image passes Test-NativeImage.ps1: real nonroot PID1, read-only
  root, dropped capabilities, health checks, stale watchdog identity rejection,
  same-user TERM and clean stopped health. Only test-owned containers, volumes
  and the internal network are removed.
- The root file manifest verifies completely. Full and minimal executable SHA:
  `c4c95fbc8d165e43a72e06644afbfba8c569f7b3a3bf23cf6e46b8862dd552b4`.
  Loader SHA remains
  `69948f5005c308166f7de48a0520edde792ad786ed3bf52d6d0d674d6b055a3c`.

Local evidence: artifacts/build/runtime-slim-20261102. Raw private inputs remain
outside source and deployment outputs. Router authentication/recovery needs a
future candidate field test; only amd64 is validated here. Prior full-runtime
field acceptance does not automatically transfer to a new runtime filesystem.

## Developer build targets

```sh
docker build --target runtime-slim -t esurfing-dialer-native:v3.0.0-minimal-glibc-candidate-amd64 Products/DockerCpp
docker build --target runtime-full -t esurfing-dialer-native:v3.0.0-full-control-amd64 Products/DockerCpp
docker build --target builder -t esurfing-build:runtime-slim-builder Products/DockerCpp
```

The internal verification runner is scripts/Test-MinimalRuntime.ps1. It requires
the three image tags, a new output directory and optional private corpus paths.
No deployed tag is overwritten by these candidate commands. Keep the known
working full image and device state for rollback. The richer failure-diagnostic
requirements remain a separate formal-release prerequisite.

# Lean glibc runtime candidate

The runtime-slim target now builds libxml2 without ICU and builds libcurl with
HTTP/HTTPS only. It uses the exact Debian source versions of the development
packages installed by the build, including Debian patches. Source archive hashes,
versions, CMake configuration and copyright notices are kept in the runtime's
/app/licenses/native-deps directory. Update/rebuild this candidate with the
distribution's security updates; custom builds do not update themselves.

The runtime-full target and default target retain the established distribution
libraries. runtime-slim-control preserves the previous complete minimal root.
This change does not replace any deployed image, identity, configuration or ZIP.

## Retained compatibility

- glibc, OpenSSL verification and trust anchors, complete timezone data, NSS/DNS,
  threaded resolver, HTTP/HTTPS, gzip support, request limits and timeouts.
- The C++ authentication/cipher implementations and loader data are unchanged.
- UTF-8/ASCII conversion in libc; selected iconv modules for GB18030, GBK/CP936,
  GB2312, Big5, Big5-HKSCS, UTF-16/32, ISO-8859-1/15 and Windows-1252.
- Chinese log text is UTF-8. Trimming iconv modules does not remove Chinese fonts:
  fonts are supplied by the terminal/client, not this container.
- Shell, jq and utilities used by health checks, watchdog guards, log-directory
  permissions, source validation and bounded diagnosis.

## Intentional exclusions

- ICU, LDAP, SSH, RTMP and their unused dependency chains.
- Non-HTTP protocols, HTTP/2, IDN translation, Brotli and Zstd. The application
  does not request compressed HTTP bodies; gzip library support remains.
- Other iconv encoding families. Unsupported conversions fail rather than silently
  transliterating. This is a defined compatibility boundary, not universal charset
  support; newly observed server encodings need explicit tests and module additions.
- Bash, ldd, getent and sleep from the lean runtime. Inspect the trusted native
  executable's resolved dependencies with:

```sh
docker exec CONTAINER sh -c 'LD_TRACE_LOADED_OBJECTS=1 /app/esurfing-native'
```

Build tools, source archives, development headers and CMake package files stay in
build stages. Only required custom shared libraries and their dependencies are
copied into the runtime. LD_LIBRARY_PATH=/opt/native-deps/lib selects those builds.

## Build and validation

```sh
docker build --target runtime-slim -t esurfing-dialer-native:v3.0.0-lean-candidate-amd64 Products/DockerCpp
docker build --target builder-lean -t esurfing-build:lean-verification Products/DockerCpp
```

runtime-platform-regression now checks twelve encoding conversions, GB18030 XML,
DNS, three timezones, CA trust, trusted synthetic HTTPS and rejection of an
untrusted certificate/hostname mismatch. SLIM_TEST_LEAN_PROFILE additionally
requires iconv without ICU and only HTTP/HTTPS protocols with SSL and async DNS.
Test-MinimalRuntime.ps1 sets this for the lean image using its profile label.
Actual runtime checks, private module vectors, lifecycle and measured sizes are
recorded under artifacts/build/runtime-lean-20261106. Measurements and remaining
field/architecture limitations are reported after the candidate checks complete.

## Measured amd64 results

| Metric | Previous deployed-test image | Lean candidate |
| --- | ---: | ---: |
| Expanded root filesystem | 75.41 MiB | 24.73 MiB |
| Compressed Docker image export (gzip level 6) | 28.50 MiB | 8.14 MiB |
| Character conversion modules/configuration | 7.71 MiB | 0.82 MiB |

Expanded size decreased by 67.2%; compressed image export decreased by 71.5%.
The export was compressed to a counting sink; no deployment ZIP/archive was
generated. ZIP overhead and host Docker engine, caches, old images, volumes and
logs are not included. These disk figures are not RAM measurements. The baseline
is the previously delivered test image; the candidate also includes intervening
review fixes, so this is an end-user image comparison, not identical binaries.

Validated candidate: esurfing-dialer-native:v3.0.0-lean-candidate-amd64.

- Linux builder: 29 passed, 2 optional private-corpus tests skipped by default.
  Strict lean-feature assertions were enabled in this final build.
- Both actual runtime images: twelve charset cases, GB18030 XML, internal DNS,
  three timezones, CA trust, trusted/untrusted/mismatched-host HTTPS, HTTP
  regression, SM4, and nine private module/vector corpora passed. Each private
  corpus passed 331 checks with 21 requests and 21 independent native responses.
- Nonroot PID1, read-only root, dropped capabilities, real health checks, watchdog
  identity rejection, same-user TERM and clean stopped health passed on candidate.
- Candidate source-input/executable manifest and all runtime file hashes verified.
- The comparison uses a separately built test program for each XML ABI: the
  upstream CMake XML build's SONAME differs from Debian's library naming. Both
  compile the same platform test source; this avoids accidentally loading a test
  program against the wrong library.

The verification runner reused the cached Debian builder and current build/collect
scripts. A fresh networked build of every default Dockerfile stage was not repeated.
No ARM build, router authentication, power-cut test or long-run acceptance was
performed for this new filesystem. Existing deployment packages remain intact.

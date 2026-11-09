# Independent cipher provenance

Only cipher code was imported from BadGhost520/ESurfingClient-CVersion, commit
f31a19d5c9440fae421df4e4277c4d350df3bf01 (v2.2.1-r7):
https://github.com/BadGhost520/ESurfingClient-CVersion . The upstream Apache-2.0
text is retained in ciphers/LICENSE. File identities and local changes are
recorded in ciphers/provenance.json.

factory.c retains algorithm constructors only. Upstream application global state,
thread indexing and logging were removed; its entry is es_create_cipher. Cipher
files, headers and the independent SimEvp compatibility layer were copied as
listed in provenance.json. No upstream Web, Mongoose, account, networking or
supervision application was imported. C++ adds bounded input/strict hex checks,
per-instance ownership and synchronization without changing reference algorithms.

A factory entry or self round-trip is not native ARM32/2082 equivalence or campus
server acceptance. Windows field candidates do not establish Docker equivalence.
Unknown modules are rejected; upstream unbounded reacquisition is not reused.

Local changes: SBOX shifts in sm4_common.c and three promoted uint16_t shifts in
snow3g_variant.c use explicit uint32_t shifts to remove UBSan failures. Both
original hashes and local_sha256 are retained. tests/reference-vectors.tsv has
475 outputs generated from the unmodified reference image with synthetic all-a
inputs; the modified outputs match. These are not server/ARM native golden
vectors and contain no user accounts or tickets. See scripts/Verify-Sources.ps1.

On 2026-10-30, a bounded (1 MiB) es_zuc_transform byte API was appended to zuc.c,
reusing its existing state initialization/processing without changing the
algorithm. local_sha256 identifies this local file. Production module decoding
uses system LibLZMA and OpenSSL libcrypto under their package licenses.
profiles/android32-2082-loader.so is a read-only copy of an existing Docker native
asset; provenance/hash are in profiles/README.md. It is not part of the Apache-2.0
reference C code. src/native_cipher.cpp is a parameterized implementation restored
from local native-boundary/differential evidence and contains no field key/IV.

# Android32/2082 loader data

android32-2082-loader.so is a byte-identical copy of the existing repository asset
Products/Docker/src/main/resources/libdaproxy.so (30180 bytes). SHA-256:

69948f5005c308166f7de48a0520edde792ad786ed3bf52d6d0d674d6b055a3c

Production C++ first verifies the complete digest, then reads recognized literals
from ELF data. It does not use dlopen/JNI or execute the ARM code and needs no
Java/emulator. The default path is /app/module-profiles/android32-2082-loader.so.
NATIVE_MODULE_LIBRARY may select a file but the same digest remains mandatory;
no alternate library is searched for or downloaded.

This is provenance for an existing native binary asset. Copying it does not
assign a new license or place it under the reference C code Apache-2.0 license.
Field module.bin, real keys/IVs, memory dumps and private logs are not stored here.

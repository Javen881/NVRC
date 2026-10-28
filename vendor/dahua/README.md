# Dahua NetSDK test dependency

This directory contains Android wrapper code and proprietary Dahua NetSDK binaries obtained from
<https://github.com/faradzhalelov/dahua_net_sdk> at tree commit
`fd3006c6bbeb7f783579d25a51ff0433c90b67f8` for the user's internal device testing.

The repository wrapper is MIT licensed. The bundled Dahua SDK JAR/SO files remain proprietary and
subject to Dahua's license. Replace them with a matching package obtained from Dahua before release.

The arm64 Dahua `libcrypto.so` and `libssl.so` are kept under `reference/` rather than packaged,
because their filenames collide with the Uniview runtime. Dahua's included core libraries do not
declare either filename as a direct dependency. The duplicate armeabi-v7a `libgnustl_shared.so` is
also kept under `reference/`; the packaged Hikvision runtime provides the shared filename.

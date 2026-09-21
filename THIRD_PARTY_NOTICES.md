# Third-party components

## EasyTier

- Upstream: https://github.com/EasyTier/EasyTier
- License: GNU LGPL v3.0; copyright remains with the EasyTier contributors.
- License text: [LICENSE](LICENSE), with the incorporated GPL v3 text in [COPYING](COPYING).
- Root manager source: `tools/root-manager-client/`; its EasyTier dependency is pinned
  to tag `v2.6.4`, with the resolved revision recorded in `Cargo.lock`.
- Bundled VPN library: `app/src/main/jniLibs/arm64-v8a/libeasytier_ffi.so`.
  The exact source revision/build recipe for this prebuilt library is not recorded
  in this repository. It must be established before distributing binary releases
  with complete corresponding-source information.
- `backup_official_migration_base/` contains historical migration sources and a
  historical FFI binary; it is not part of the Android build. The provenance of
  that historical binary likewise needs to be recovered.
- Root mode downloads the official EasyTier core separately, or imports a user-provided core.

## Other dependencies

AndroidX/Jetpack Compose and kotlinx.coroutines are obtained by Gradle;
Rust dependencies are recorded in `tools/root-manager-client/Cargo.lock`.
Their original licenses and copyright notices continue to apply.
This notice does not relicense third-party code under MoonTier's license.

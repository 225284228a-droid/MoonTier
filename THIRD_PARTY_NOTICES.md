# Third-party components

## EasyTier

- Upstream: https://github.com/EasyTier/EasyTier
- License: GNU LGPL v3.0; copyright remains with the EasyTier contributors.
- License text: [LICENSE](LICENSE), with the incorporated GPL v3 text in [COPYING](COPYING).
- Root manager source: `tools/root-manager-client/`; its EasyTier dependency is pinned
  to tag `v2.6.4`, with the resolved revision recorded in `Cargo.lock`.
- Bundled VPN library: `app/src/main/jniLibs/arm64-v8a/libeasytier_ffi.so`.
  It is built from EasyTier commit `5af90471bfe654d097f7e488a2699f2850db7dd2`
  plus [the Android FFI patch](tools/ffi-runtime/easytier-ffi-android.patch).
  The [build recipe](tools/ffi-runtime/README.md) uses Rust 1.96.0, upstream
  `Cargo.lock`, default `c-abi`/`ffi-dataplane` features, and NDK 27.2.12479018.
  The patched checkout is local build output and does not modify upstream.
  SHA-256: library `c3c247b96c9eb58285e05da9098d7eae0656e2580bc158f61ff9cea8be076559`;
  patch `ab684bf4687c4201032e67a183e4d7057d1b264f3087e49d4e77ca20f06206c4`.
- `backup_official_migration_base/` contains historical migration sources and a
  historical FFI binary; it is not part of the Android build. The provenance of
  that historical binary likewise needs to be recovered.
- Root mode downloads the official EasyTier core separately, or imports a user-provided core.

## Other dependencies

AndroidX/Jetpack Compose and kotlinx.coroutines are obtained by Gradle;
Rust dependencies are recorded in `tools/root-manager-client/Cargo.lock`.
Their original licenses and copyright notices continue to apply.
This notice does not relicense third-party code under MoonTier's license.

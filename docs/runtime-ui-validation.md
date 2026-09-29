# Runtime and UI validation

Verified on 2026-09-30.

## Sources

- FFI / official Android baseline: EasyTier
  `ff3921ce6842f9660d629eafac499498026c6784`, plus
  `tools/ffi-runtime/easytier-ffi-android.patch`.
- Root manager client: EasyTier `v2.6.4`, resolved by the committed
  `tools/root-manager-client/Cargo.lock`. Runtime core versions remain independent.
- Root helper installation version: `2`.

## Automated checks

- Kotlin unit tests: 12 passed. Cases cover typed JSON null/error handling,
  connection selection by peer ID and default connection, absent/closed samples,
  rounding, real high RTT, a 1000 ms route estimate with a 12 ms connection RTT,
  disconnect/reconnect, independent FFI settings, retained Web configuration,
  and invalidation of old TUN requests after network/route changes.
- Root helper: `cargo zigbuild --target aarch64-unknown-linux-musl --release
  --offline -j 2` passed. ELF64 AArch64, statically linked (no INTERP segment).
  Bundled SHA-256:
  `c7d6424b3dcfb97249f89ccd5c19b55e50593dbeb366b569fb5aee39a026086c`.
- Android `testDebugUnitTest lintDebug` passed with frozen Kotlin sources:
  12 tests, zero failures; lint zero errors and nine warnings (dependency
  updates, compatibility/resource recommendations, hardware ID use, and the
  application-context controller singleton).
- Android arm64 FFI release build, JNI/CMake build, and final `assembleDebug`
  passed. FFI source library SHA-256:
  `a4952636ac130cd4432fd558989d62ef4aa2338ea356ed03a177a2d843c0ec37`.
  Source patch SHA-256:
  `0ae19a856cd98a865c1022a5b5c74f89c5655540da92bdc1a998a494d65408a6`.
- `zipalign -c -P 16 4` passed on `app/build/outputs/apk/debug/app-debug.apk`.
  All LOAD segments of all three packaged arm64 libraries are 16 KiB aligned.
  Dependencies are Android platform libraries or the bundled FFI library.
- APK entry SHA-256 values match the Root helper asset and all three stripped
  native build outputs. Packaged FFI SHA-256 (after Android symbol stripping):
  `95aecbf7eacb9ad3e7103200fac1a7f038a1b78c7fde4e734921267852f80e4b`.
- Rust host tests: 8 passed. Six FFI cases cover saved/disabled configurations,
  the real `get_config` RPC request, detach/reconnect, full stop after a local
  Web-instance start, rollback tracking, and callback delivery/unregistration.
  Two core TestHost cases cover concurrent local/remote mutation ordering and
  rollback restoration hooks. Test filters are checked for the expected counts.
  Final host-test corrections are confined to `cfg(test)`; production Rust
  sources did not change after the verified arm64 build.

## Device acceptance not executed

`adb devices -l` returned no devices. The following checks require an Android
device and were not claimed as passing:

1. In light/dark themes and portrait/landscape, open both ordinary selectors and
   the Root current-configuration selector. Check long names, selection/running
   markers, dismissal, stable surrounding layout, and fold/page transitions.
2. Run automatic and manual official-server synchronization. Check immediate
   rotation/loading text, repeated taps, success count, failure reason, retained
   previous list, and successful retry.
3. Connect FFI using a username and a full URL. Test remote create/update/delete,
   local TUN priority, remote TUN replacement, and concurrent no-TUN instances.
4. Clear and save the FFI server field. Verify that running instances remain;
   reconnect and restart the app, verifying UUID, Web source, enabled state, and
   cached disabled configurations. Also restart with all instances disabled and
   the server field empty: cached Web configurations must remain available to
   enable. Root settings must remain independent.
5. Cancel VPN authorization, explicitly retry it, recreate the Activity, revoke
   VPN permission, and change routes/DNS/IP while attaching. Old service intents
   or cleanup must never replace/close a newer TUN.
6. Switch Root/FFI modes while starting, stopping, polling, and connecting to a
   configuration server, including rapid switching back. Verify old-mode service
   shutdown before target-mode operation.
7. Compare against the pinned official Android client and another official
   platform client. Record the first native positive `stats.latency_us` sample,
   its first visible value, and each direct/relay route transition. Before a
   sample, expect pending measurement; real 500/1000 ms RTT must remain visible.

The display intentionally uses connection RTT, not `path_latency`. End-to-end
RTT/display timing, Android lifecycle behavior, and visual quality remain device
acceptance items even when unit tests and builds pass.

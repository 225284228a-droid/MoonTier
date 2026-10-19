# Runtime and UI validation

Validation date: 2026-10-20.

## Sources

- Bundled Android FFI: EasyTier `5af90471bfe654d097f7e488a2699f2850db7dd2`
  (2.7.0 development line with shared mobile TUN), plus the committed
  `tools/ffi-runtime/easytier-ffi-android.patch`. Recipe: `tools/ffi-runtime/README.md`.
- Root control helper remains built against the v2.6.4 RPC schema. Downloaded
  Root cores are independent of the bundled FFI and can use stable, prerelease,
  or nightly upstream assets.

## Focused automated checks

- `gradle testDebugUnitTest`: 19 tests, zero failures. New coverage verifies
  multiple IPv4/IPv6 addresses, combined routes/DNS/MTU, stable member ordering,
  invalidation after member changes and same-UUID instance replacement,
  release-channel selection, nightly asset
  names differing from the tag, build timestamps, settings persistence, and
  missing architecture assets. A Root routing case verifies all active subnets,
  removal of stopped/no-TUN members, and exit-node reachability within its owner.
  Existing runtime and node-parser cases pass.
- `bash tools/test-root-lifecycle.sh`: six cases pass: stale PID, zombie,
  reused PID, graceful shutdown, forced shutdown, and a process that refuses
  to exit. The last case must return failure and retain the PID file.
- The Android shell asset is forced to LF in Git and normalized when installed.
  Shutdown checks process identity and waits before permitting a mode change;
  a zombie no longer causes the app's previous extra `kill -0` check to fail.
- Shared TUN member selection, subnet conflicts, and packet dispatch use the
  upstream implementation. The patch exposes its mobile projection and
  synchronous attachment functions to JNI and removes local/Web preemption.
  Optional build-script host tests are limited to upstream's four mobile TUN
  selection cases. Neither those optional host tests nor the full Rust suite
  were run during this change.

- Android arm64 FFI release build completed and all ELF LOAD segments passed
  the build recipe's 16 KiB alignment check. Source library SHA-256:
  `c3c247b96c9eb58285e05da9098d7eae0656e2580bc158f61ff9cea8be076559`.

- Final `gradle testDebugUnitTest assembleDebug --offline` passed, including
  JNI/CMake linking and APK packaging. The APK contains the rebuilt FFI library
  and the LF-normalized Root shell asset.

## Device acceptance still required

`adb devices` returned no connected devices. No real-device UI or network
acceptance is claimed. The main follow-up cases are:

1. Start two local configurations on disjoint subnets and a Web configuration.
   Verify one Android VPN interface contains all accepted addresses/routes,
   and each configuration has independent nodes and start/stop controls.
2. Introduce an overlapping subnet. Verify the conflict appears on that member
   and existing members stay enabled. Stop/restart one accepted member while
   traffic flows on another. Repeat with IPv6 and shared DNS.
3. Cancel and retry VPN permission; recreate/revoke the VPN service and change
   DHCP addresses/routes while attachment is in flight. Obsolete requests and
   cleanup must not replace or detach the newer shared VPN.
4. Switch Root to VPN and back with live networks, a stale PID, and a stopped
   core. Confirm the old backend exits before target-mode operations.
5. In Settings, choose each core source, check/download a core, and import a
   local ZIP. Confirm a running Root manager restarts after replacement.
6. Inspect configuration selectors, independent cards, long names, and theme
   changes on an Android device.

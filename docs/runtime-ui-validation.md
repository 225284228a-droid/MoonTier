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

## Device checks (2026-10-20)

Device: Xiaomi 23113RKC6C, arm64. The APK was signed with the project's existing
local signing configuration and upgraded in place without uninstalling the app.

- Two isolated static-IP profiles ran together in VPN mode. `ip -4 addr` showed
  `172.30.240.1/24` and `172.30.241.1/24` on the same TUN, and Android's VPN
  routing table contained both subnets.
- Stopping one profile removed its address and left the other profile enabled
  with its own TUN address. Both profiles remained independently controllable.
- Switching a running VPN to Root initially exposed a bound-service lifecycle
  issue: `stopService` did not destroy the system-bound VpnService, leaving the
  descriptor and VPN rules alive. The controller now explicitly awaits pending
  attachment, native detach, and descriptor close before completing shutdown.
- After that fix, switching a live VPN to Root left no app VPN service or TUN.
  Root ran the two isolated subnets with two priority-20001 policy rules.
  Switching Root back to VPN succeeded and removed both policy rules and TUNs.
- The three download source choices worked on-device. Update checks returned
  stable `v2.6.4`, prerelease `v2.7.0`, and a timestamped nightly build. The
  installed Root core remained v2.6.4; no downloaded core was installed.
- No AndroidRuntime crash appeared during these checks. Original profiles and
  settings were restored, and temporary profile/runtime/Root files were removed.
- The final signed debug APK was rebuilt with all 19 unit tests passing and
  installed on the device.

## Not covered by this focused device pass

Live remote configuration-server mutations, peer-to-peer data transfer, subnet
conflicts, IPv6/shared-DNS traffic, permission revocation races, downloading and
replacing a live Root core, and full visual/theme acceptance were not exercised.

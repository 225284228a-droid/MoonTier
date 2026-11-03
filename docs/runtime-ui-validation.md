# Runtime and UI validation

Validation date: 2026-10-20.

## Sources

- Bundled Android FFI: EasyTier `5af90471bfe654d097f7e488a2699f2850db7dd2`
  (2.7.0 development line with shared mobile TUN), plus the committed
  `tools/ffi-runtime/easytier-ffi-android.patch`. Recipe: `tools/ffi-runtime/README.md`.
- Root control helper is built against the upstream `5af90471` (2.7.0
  development) schema so it can carry the current configuration fields. Downloaded
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


## Configuration form and selector update (2026-10-20)

- Audited the pinned upstream `common.Flags` schema: all 42 non-deprecated
  flags have controls in the configuration form. New controls cover compression,
  worker count, receive/foreign relay limits, socket mark, device name, DNS zone,
  broadcast forwarding, data relay policy and preferred peer relay.
- Added IPv4/IPv6 DHCP CIDRs, static IPv6, public IPv6 allocation/provider,
  secure mode and node/peer keys, managed credentials, TCP/UDP allowlists,
  TCP/IPv6 STUN, mapped listeners, SOCKS5, proxy subnet mapping/group access,
  port forwards, and WireGuard portal/client configuration.
- Advanced values persist in the profile JSON and round-trip through TOML.
  Unknown root keys, flags and extra sections remain available through raw TOML;
  ACL remains in its existing TOML editor. Host filesystem/namespace and source
  metadata settings remain raw TOML rather than Android form controls.
- Save/export now apply pending raw TOML edits and ask the bundled core to parse
  the result before accepting it. Unsigned numeric inputs are checked locally;
  uint64 values above TOML's signed integer range use protobuf string encoding.
  Compression values use the core's canonical enum names (`None`, `Zstd`).
- Renamed the first tab/header to “运行状态”, removed the redundant concurrent
  profile descriptions, and styled the shared selector container, rows,
  selected state, border and shape with the app's light/dark palette.
- `testDebugUnitTest assembleDebug --offline` passed: 25 JVM tests, zero failures.
  Six new cases cover advanced configuration/storage round trips, older-profile
  defaults, static-address import, extension preservation, string quoting and
  unsigned integer bounds.
- ADB was initially unavailable, then reconnected. Installed revision `a09d230`
  in place on the same Xiaomi 23113RKC6C without uninstalling or clearing data.
- Checked the “运行状态” label, profile selector, three core-source choices,
  source-menu selection/dismissal, and the light/dark menu appearance on-device.
- In an unsaved new profile, IPv6 DHCP revealed the CIDR field and secure mode
  revealed node keys and managed credentials. Both the default profile and the
  profile with IPv6 DHCP/secure mode enabled passed the bundled native parser
  and opened Android's export picker; both exports were canceled.
- Discarded the new profile and restored the original light theme. Existing
  profiles, Root mode, source selection and installed core were preserved.
  No network instance was started in this focused UI/config-parser pass.

## Compact configuration and Root nightly follow-up (2026-10-20)

- `TomlCodec` now writes only settings that differ from the core defaults.
  DHCP mode, `private_mode` and `default_protocol` stay explicit because their
  defaults differ between EasyTier 2.6 and 2.7; compression uses the numeric
  enum so the 2.6 TOML parser does not panic on a name.
- The configuration editor groups options as basic settings, addressing,
  servers, transport, P2P/NAT, network services, proxy/port forwarding,
  performance, access control (ACL) and raw TOML. Secure mode and its node keys
  now sit in basic settings; the access whitelists sit with ACL.
- VPN mode uses the profile selector and the selected profile's status only.
  The separate "shared VPN" status card and the all-profile session list were
  removed; Root mode keeps its independent instance list.
- Selector menu rows use tighter vertical padding and title/subtitle spacing.
- The bundled Root manager client was rebuilt against upstream `5af90471`
  (2.7.0 development) and the helper version was bumped, so Root mode can parse
  the current WireGuard portal, managed-credential and DHCPv6 fields.
- WSL RPC checks: the new helper listed, started and left running both a compact
  profile and a profile with `[vpn_portal_config]` on the nightly core; the same
  helper started a compact profile on the stable 2.6.4 core. The previous helper
  rejected the new portal section with `missing field client_cidr`.
- The cross-built `aarch64-unknown-linux-musl` helper was executed under
  `qemu-aarch64-static`: it printed help, listed the manager, and started a
  compact profile against the nightly core. The debug APK contains the same
  helper bytes (SHA-256 `61d5bcab…4db82d`).
- `gradle testDebugUnitTest assembleDebug --offline` passed: 25 tests, zero
  failures, including the updated codec checks for default omission, numeric
  compression, and compact round trips.

## Config-server status and hostname resolution (2026-11-04)

- Root mode no longer resolves config-server, peer or STUN hostnames to IPv4 in
  the app before launch. The core receives the configured URLs unchanged and
  resolves them itself; upstream fixed the post-KSU resolution failure in
  `#1761` and bounded the system-resolver fallback in `#2310`.
- The shared manager now always writes its console log to the bounded
  `manager.log` instead of `/dev/null`, and `RUST_LOG` keeps the config-server
  client target at `info` even when user-facing core logs are off. The client
  lifecycle is the only core state that drives UI, so this small stream is
  retained while the log panel stays empty at the off level.
- Connection state is derived from the whole 32 KiB log tail (newest marker
  wins) and covers both current (`connected to config server`) and pre-2.7
  (`Successfully connected`) wordings plus secure-mode/heartbeat failures. A
  failure is surfaced as `连接失败` in the status card; a later reconnect clears
  it. Previously the state was derived only from the newest 80 logged lines at
  non-off levels, so default settings could never show `已连接`.
- `manager.json` now records the manager launch-environment version. A root
  manager inherited from an older app build is restarted once on the next start
  so it runs with the new status-log filter.
- `gradle testDebugUnitTest assembleDebug --offline` passed: 53 tests, zero
  failures. New cases cover both log wordings, failure/reconnect ordering,
  secure-mode and heartbeat failures, peer failures that must not flip the
  state, and the off/normal/debug `RUST_LOG` filters. No device was attached,
  so the Root config-server connection itself was not re-tested on-device.

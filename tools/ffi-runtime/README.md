# Android FFI runtime

The bundled EasyTier 2.7.0 development runtime is pinned to upstream commit
`5af90471bfe654d097f7e488a2699f2850db7dd2`, which includes the mobile shared TUN
implementation. The `v2.7.0` release tag predates that implementation.
`easytier-ffi-android.patch` applies only to this commit. Builds use upstream
`Cargo.lock` (`--locked`), Rust 1.96.0, the `aarch64-linux-android` target,
NDK 27.2.12479018 API 24 clang, default `c-abi` / `ffi-dataplane` features,
and 16 KiB ELF LOAD alignment. The isolated checkout is ignored by Git.

```powershell
.\tools\ffi-runtime\build.ps1 -SourceRepo C:\path\to\easytier
```

The source repository must contain the pinned commit. The build copies
`libeasytier_ffi.so` into the app's arm64 JNI library directory and prints
SHA-256 hashes. Optional `-RunTests` runs the four upstream mobile TUN selection
unit tests before the Android build. It does not run the full upstream suite.

## C ABI additions

- `shared_tun_config(const char **out_json)`: projects all running TUN instances
  through upstream `get_mobile_tun_config`. JSON includes accepted `instance_ids`,
  IPv4/IPv6 `addresses`, `routes`, `dns`, and per-instance `rejected` errors.
  Free the output with `free_string`.
- `attach_shared_tun_fd(int fd)`: synchronously attaches accepted members through
  upstream `attach_mobile_tun_fd`. The shared dispatcher opens the descriptor
  once. A nonpositive descriptor detaches all members. The caller owns the fd
  and must keep it open until synchronous detach completes. Android serializes
  attach/detach and ignores cleanup from an obsolete VPN generation.
- `run_network_instance_with_source(const char *toml, int source)`: `0=user`,
  `1=web`. Starting a network never preempts other networks. Shared TUN conflicts
  are reported per member; unaffected networks stay enabled.
- `set_network_instance_enabled(const char *uuid, bool enabled)`: stop an
  instance while retaining its configuration, or reenable it independently.
- `collect_runtime_configs(const char **out_json)` and
  `restore_runtime_configs(const char *json)`: persist/restore local and Web
  identities, TOML, sources, enabled states, and no-TUN flags. Restoration requires
  an empty runtime. TOML contains secrets; persist only in private app storage.
- `detach_config_server_client_preserve_instances()`: disconnect the client and
  drain callbacks while preserving Web instances for reconnect. The separate
  `stop_config_server_client()` retains its delete semantics.
- `set_runtime_event_callback(callback, user_data)`: independent wake events for
  address, proxy route, peer, and instance changes. Null unregisters and drains.
  Callbacks only schedule Android work; never call FFI synchronously within them.

The patch also retains the existing source catalog, callback lifecycle, and
management rollback integration. Shared NIC packet dispatch, address ownership,
route conflict selection, and mobile attachment transactions come from upstream.

## Bundled build hashes

- Library SHA-256: `c3c247b96c9eb58285e05da9098d7eae0656e2580bc158f61ff9cea8be076559`.
- Patch SHA-256: `ab684bf4687c4201032e67a183e4d7057d1b264f3087e49d4e77ca20f06206c4`.

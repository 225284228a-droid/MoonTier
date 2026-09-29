# Android FFI runtime

`easytier-ffi-android.patch` applies only to EasyTier commit
`ff3921ce6842f9660d629eafac499498026c6784`. The build uses the committed
upstream `Cargo.lock` (`--locked`), Rust 1.96.0 with the
`aarch64-linux-android` target, NDK
27.2.12479018 API 24 clang and 16 KiB ELF LOAD alignment, and crate default features (`c-abi` and
`ffi-dataplane`). The isolated source checkout is `tools/ffi-runtime/upstream/`
and is ignored by Git. The original EasyTier checkout is never modified.
The patch SHA-256 is
`0ae19a856cd98a865c1022a5b5c74f89c5655540da92bdc1a998a494d65408a6`;
the resulting arm64 library SHA-256 is
`a4952636ac130cd4432fd558989d62ef4aa2338ea356ed03a177a2d843c0ec37`.

```powershell
.\tools\ffi-runtime\build.ps1 -SourceRepo C:\path\to\easytier -RunTests
```

The build copies `libeasytier_ffi.so` into the Android app's arm64 JNI library
directory and prints the patch and library SHA-256 hashes. Use a source repository
containing the exact commit above. `-RunTests` runs focused host tests before the
Android build; the verified filters cover eight host tests across the FFI and
core crates. A full suite is `cargo +1.96.0 test --locked -j 2 -p easytier-ffi --lib
-- --test-threads=1` from the patched checkout.

## C ABI additions

- `run_network_instance_with_source(const char *toml, int source)`: `0=user`,
  `1=web`. Existing `run_network_instance` is equivalent to user source.
- `set_network_instance_enabled(const char *uuid, bool enabled)`: disabling
  stops the instance but preserves its configuration for later reenable.
- `collect_runtime_configs(const char **out_json)`: caller frees the allocated
  output with `free_string`. JSON array objects contain `instance_id`,
  `instance_name`, `source` (`user` or `web`), `toml_config`, `enabled`, `no_tun`.
- `restore_runtime_configs(const char *json)`: restores a snapshot only into
  an empty runtime; disabled entries remain registered without starting.
- `detach_config_server_client_preserve_instances()`: disconnects and drains
  callbacks without deleting Web-owned instances. A reconnect adopts those
  running Web instances; `stop_config_server_client()` retains delete semantics.

The TOML snapshot contains network secrets. Android persistence must use private
app storage and avoid logging this JSON. No FFI call may be made synchronously
inside the config-server callback. Web TUN starts preempt other Web TUNs and
reject an active User TUN; User TUN starts preempt all TUNs. `no_tun` starts do
not preempt. Preempted configurations remain disabled in the snapshot. Callback
events include `instance_id` and `disable_network_instance` for Web preemption.
Android's TUN adapter duplicates the caller's fd synchronously on `set_tun_fd`;
the caller retains and may close its own fd after success. The queued duplicate
is RAII-owned and closed after the NIC's I/O tasks have stopped.
`set_runtime_event_callback(callback, user_data)` listens independently of the
Web client, emitting JSON `dhcp_ip_changed`, `proxy_cidrs_updated`,
`peer_connections_changed`, `instance_exited`, and `event_lagged` wake events
with `instance_id`; null
unregisters and drains delivery.

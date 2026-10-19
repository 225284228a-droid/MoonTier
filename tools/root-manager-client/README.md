# MoonTier Root manager client

This is a small control-plane client for EasyTier's official `WebClientService` RPC.
It mirrors the calls used by the Linux GUI service mode and never carries VPN data.

The source is built against the upstream `5af90471` (2.7.0 development) schema so it
can compile every configuration field MoonTier's form exposes, including the current
WireGuard portal, managed-credential and DHCPv6 sections. Runtime compatibility stays
capability-based: no core version is checked or pinned. Cores that provide the
official `WebClientService` are used, while older cores without that service return
their native RPC error. Base fields remain compatible with the 2.6.4 stable core;
2.6-only WireGuard portal layouts and other settings removed upstream are not
re-emitted by this client.

The Android app bundles the static `aarch64-unknown-linux-musl` release binary as
`app/src/main/assets/root/moontier-root-manager`. The separately downloaded official
`easytier-core` remains the only Root-mode network core.

Build with:

```powershell
rustup target add aarch64-unknown-linux-musl
cargo zigbuild --manifest-path tools/root-manager-client/Cargo.toml --target aarch64-unknown-linux-musl --release
```

# Forwarding Engine Selection (T040)

**Date**: 2026-10-11
**Status**: Source audit complete. Selection is provisional until the forwarding
qualification in [vpn-feasibility.md](vpn-feasibility.md) (T050) passes on devices.

## Decision

Use **hev-socks5-tunnel** (TUN → lwIP user-space stack → SOCKS5) with an embedded,
authenticated, loopback-only SOCKS5 relay written in Kotlin (`LocalSocksRelay`). The relay
opens every egress socket itself and passes it through `VpnService.protect` before use, and
it applies traffic shaping (`TrafficShapingScheduler`). There is no remote gateway.

```text
apps ─► TUN ─► libkg-tunnel.so (hev-socks5-tunnel + lwIP) ─► 127.0.0.1 SOCKS5 relay (Kotlin)
                                                               │  per-start username/password
                                                               ▼
                                         protected TCP/UDP sockets ─► Wi-Fi / cellular
```

## Pinned source

| Component | Repository | Commit | License |
| --- | --- | --- | --- |
| hev-socks5-tunnel | https://github.com/heiher/hev-socks5-tunnel | `07bea57d20f71c9804055c2e2a0787d65a7277cb` (2026-10-08, tag 2.18.0 + 6) | MIT |
| src/core (hev-socks5-core) | submodule | `a5cc1a6351d3d9f59c00c06547fa5e6ab85e4242` | MIT |
| third-part/hev-task-system | submodule | `e20b98914332613a5ed9da46ab5f1e894f3d3ef5` | MIT |
| third-part/lwip | submodule | `150d2b1a2658612741b4ac002578d440e7874540` | BSD-3-Clause (lwIP) |
| third-part/yaml | submodule (hev fork of libyaml) | `efa36117a8646d26d12b58e05bac472d7854a70d` | MIT |
| third-part/wintun | prebuilt Windows driver | not built for Android | Wintun prebuilt license (unused) |

Pinned to the commit after tag 2.18.0 that was audited, because it includes "HevMain:
Release lwIP state on exit (#336)". Kids Garden stops and restarts the tunnel inside one
process (stop, revoke, restart from setup), so lwIP state must be released on exit.

The repository adds the source as a git submodule at
`app/src/main/cpp/hev-socks5-tunnel`. The build compiles only the arm64-v8a and x86_64
ABIs with `-Wl,-z,max-page-size=16384` (16 KB page compatible). First local build
(2026-10-11, NDK 28.2.13676358): every LOAD segment of `libkg-tunnel.so` is 0x4000 aligned
and only `NativeTunnel.run` and `NativeTunnel.quit` are exported as JNI entry points.

On Windows, the submodules' public headers are symlinks and need `core.symlinks=true` with
Developer Mode (see quickstart.md); the build stops early with that instruction otherwise.

## Audit checklist

| Criterion | Finding |
| --- | --- |
| Source available, maintained | Yes; active upstream, releases through 2.18.0 (2026-09-27) |
| License | MIT plus lwIP BSD-3-Clause; compatible with a free app. Attribution required in the app's licenses notice |
| Android 15/16 | Upstream ships an Android `Android.mk` and is used by Android VPN apps; device qualification still required (T050) |
| TCP / UDP | TCP via SOCKS5 CONNECT; UDP via standard SOCKS5 UDP ASSOCIATE (`udp: 'udp'`) |
| IPv4 / IPv6 | Both; requests carry numeric addresses (ATYP 1 and 4) |
| QUIC | Carried as ordinary UDP; no special handling or blocking |
| 4 KB / 16 KB pages | Upstream links with 16 KB max page size; `APP_SUPPORT_FLEXIBLE_PAGE_SIZES`; must be verified on a 16 KB device or emulator |
| Remote gateway | None. The SOCKS server is our own relay on 127.0.0.1 |
| Content / DNS-name inspection | lwIP handles headers only. Mapped DNS (`mapdns`) is a hostname-mapping feature and is **never configured**. The relay refuses domain-name requests (ATYP 3) without resolving them |
| Destination history | None kept. The relay's only mapping is resolver address ↔ virtual resolver address for reply addressing, capped at 8 entries per UDP association and cleared when it closes |
| Logging | Logger writes only when `misc.log-file` is set; Kids Garden never sets it, so the logger fd stays closed. Config errors and the task system's stack-overflow detector print with `fprintf(stderr)`; `hev-debugger.c` prints only with `ENABLE_DEBUG`, which is not defined. Our JNI bridge redirects stdout and stderr to `/dev/null` at load, so no native path can print |
| Config files | Upstream JNI takes a file path; it is excluded. Our JNI calls `hev_socks5_tunnel_main_from_str`, so the config (with per-start relay credentials) stays in memory and is zeroed after use |
| Statistics | `hev_socks5_tunnel_stats` exists upstream but is never called or exported |
| Socket protection | Native code connects only to 127.0.0.1, which never routes into the VPN. All egress sockets are created and protected in Kotlin, so no native protect bridge is needed |
| Relay exposure | Bound to 127.0.0.1 only, rejects non-loopback peers, and requires a random 16-byte username and password (constant-time comparison) per start, because other apps can reach loopback ports |

## Rejected options

| Option | Reason |
| --- | --- |
| Read/sleep/drop packets from the TUN directly | Breaks TCP and UDP semantics; no user-space stack (research R2) |
| Home-grown TCP/IP stack | Retransmission, fragmentation, IPv6 and UDP mapping exceed V1 scope |
| xjasonlyu/tun2socks (Go, gVisor) | Larger runtime and Go embedding work; Android not a listed primary platform |
| hev-socks5-tunnel with a remote SOCKS server | Remote gateway is prohibited (Constitution III) |
| hev-socks5-server as the local relay | A second native component; shaping and `VpnService.protect` are simpler in Kotlin |
| Upstream `hev-jni.c` | Needs a config file on disk; replaced by an in-memory bridge |

## Open items before release

- Run the T050 qualification and record results in [vpn-feasibility.md](vpn-feasibility.md).
- Measure thread usage: the relay uses two threads per active flow (128 KiB stacks), capped
  at 1024 flows.
- Add third-party license notices (MIT, lwIP BSD-3-Clause) to the app.
- Re-audit logging and mapped-DNS paths whenever the pinned commit changes.

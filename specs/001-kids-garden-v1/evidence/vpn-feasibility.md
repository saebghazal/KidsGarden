# VPN Forwarding Qualification (T050, G-VPN)

**Status**: NOT RUN. No device or toolchain was available when the engine was written. The
G-VPN gate stays closed until every row below has a recorded synthetic result.

Use only synthetic traffic from test devices and test servers you control. Never capture a
real child's traffic. Record numbers, not packet contents.

## Setup

- Devices: one Android 15 (API 35) and one Android 16 (API 36) physical device, plus a 16 KB
  page-size emulator image.
- Networks: Wi-Fi (dual stack) and cellular.
- Build: debug APK with the hev-socks5-tunnel submodule checked out (see
  [forwarding-engine-selection.md](forwarding-engine-selection.md)).
- Baseline: the same measurement with the VPN off.

## Results

| # | Check | Pass condition | API 35 | API 36 | 16 KB |
| --- | --- | --- | --- | --- | --- |
| 1 | TCP download/upload | Completes; no stalls or resets | | | |
| 2 | UDP and QUIC (HTTP/3 page load) | Loads over QUIC, not only TCP fallback | | | |
| 3 | IPv4 and IPv6 destinations | Both reachable | | | |
| 4 | DNS (plain UDP, TCP, Private DNS automatic) | Names resolve through the virtual resolver | | | |
| 5 | MTU | Large transfers work; no fragmentation black holes | | | |
| 6 | Wi-Fi ↔ cellular handover | New connections work after handover; DNS follows the new network | | | |
| 7 | Concurrent flows (≥200) | All progress; no crash; memory stays bounded | | | |
| 8 | Normal overhead | ≥ 90% of VPN-off throughput, ≤ 50 ms added RTT | | | |
| 9 | Gentle profile | ≈ 256 kbps per direction, ≈ +300 ms RTT | | | |
| 10 | Medium profile | ≈ 128 kbps per direction, ≈ +600 ms RTT | | | |
| 11 | Strong profile | ≈ 64 kbps per direction, ≈ +1000 ms RTT | | | |
| 12 | Exclusions | Dialer, default SMS app and WhatsApp traffic bypass the VPN; other traffic stays dull | | | |
| 13 | Stop / revoke | Network keeps working with ordinary routing; no black hole | | | |
| 14 | Restart in the same process | Second start reaches RUNNING | | | |
| 15 | No output | `logcat` and the app's files show nothing from the engine or relay in error paths | | | |
| 16 | Native packaging | Library loads on 4 KB and 16 KB page devices | | | |

## Notes

Record tool versions, server locations and dates with each result.

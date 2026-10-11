# Privacy Audit (T086, G-PRIVACY)

**Date**: 2026-10-11
**Scope**: commit after US6 on branch `001-kids-garden-v1`
**Status**: Source and manifest audit complete. Device checks and the release dependency
report are **not yet run** (no toolchain or device was available), so G-PRIVACY stays open
until the "Pending" rows are filled in.

Method: the searches from [checklists/privacy.md](../checklists/privacy.md), run over
`app/src/main`, `domain/src/main`, the Gradle files, and the manifest. Each hit was read in
context. The reviewer-owned checklist itself is left for the human reviewer to tick.

## Logging and telemetry

| Check | Result |
| --- | --- |
| `android.util.Log`, Timber, `println`, `printStackTrace`, `__android_log`, `printf` in Kotlin and our C | None. One hit is a comment in `kg_tunnel_jni.c` describing how output is suppressed |
| Native library output | Upstream hev-socks5-tunnel has `fprintf(stderr)` in config errors and the task system's stack-overflow detector. `kg_tunnel_jni.c` points stdout/stderr at `/dev/null` in `JNI_OnLoad`, and the config never sets `log-file`, so the logger stays closed. See [forwarding-engine-selection.md](forwarding-engine-selection.md) |
| Analytics, crash reporting, ads, tracking | None in `gradle/libs.versions.toml` or any build file. Libraries: AndroidX core/activity/Compose/navigation/lifecycle/DataStore, kotlinx coroutines and serialization, and test-only JUnit/AndroidX test |
| Error text | Errors are typed reason codes (`EngineFailure`, `VpnUnavailableReason`, `SettingsFailure`, `PinOutcome`). The only exception message used is our own fixed validation text inside `SettingsFailure.InvalidValue`; it is never displayed, printed, or stored |

## Storage

| Check | Result |
| --- | --- |
| Persisted fields | Only `SettingsDocument` (schemaVersion, PIN verifier, dull intensity, VPN preference, profile table, Soft Landing length, setup flags). No mode, session, pause, token, call, or destination field |
| File writes | Only DataStore at `noBackupFilesDir/kidsgarden-settings.json`. No other `File`, `FileOutputStream`, `openFileOutput`, or `SharedPreferences` use |
| Raw PIN | Never stored: PBKDF2 verifier only. PIN text lives in plain `remember` (no saved state), char arrays are cleared after use, and no navigation argument carries it |
| Database, caches, history | None |
| Backup and transfer | `allowBackup="false"`, `dataExtractionRules` and `fullBackupContent` exclude everything |
| Session activity writes | `StoragePrivacyTest` asserts the settings file is byte-for-byte unchanged across grants, a call, expiry, and restart |

## Content and traffic

| Check | Result |
| --- | --- |
| URL, domain, DNS name, SNI, payload | The relay refuses domain-name SOCKS requests (ATYP 3) without resolving them and copies payloads as opaque bytes. Mapped DNS is never configured |
| Buffers and flows | One fixed buffer per direction per flow (8 KiB), a 16 MiB in-flight budget, and a 1024-flow cap; buffers are zeroed on close; `stop()` closes every socket |
| App-originated remote connections | None. No HTTP client in code or dependencies. The forwarding probe goes to TEST-NET-1 (192.0.2.1) through the TUN and is consumed by the local relay |
| Statistics | `hev_socks5_tunnel_stats` is never called |

## Calls and packages

| Check | Result |
| --- | --- |
| Call detection | Per-subscription `TelephonyCallback.CallStateListener` and `getCallStateForSubscription` only. No `TelecomManager.isInCall`, device-wide `getCallState`, or `AudioManager` (the only hits are comments saying so) |
| Call identity | Observations carry state, monitor generation, and time only. Subscription IDs stay inside the monitor |
| Package visibility | `<queries>` for `com.whatsapp`, `ACTION_DIAL`, and `ACTION_SENDTO smsto:` only. No `QUERY_ALL_PACKAGES` |
| VPN bypass | Only `addDisallowedApplication` for the resolved dialers, default SMS app, and WhatsApp. No `addAllowedApplication`, `allowBypass`, or always-on |

## Permissions and copy

| Check | Result |
| --- | --- |
| Permissions | INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SYSTEM_EXEMPTED, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, READ_PHONE_STATE. No contacts, call log, SMS, accessibility, usage access, overlay, or notification listener |
| Explanation before prompt | VPN, notification, and phone-state prompts each launch only from a button on their explanation screen |
| Hard-coded UI text | No `Text("…")` or literal `contentDescription` in main code; all copy is in `strings.xml` |
| Child-facing wording | No "blocked", "restricted", "time limit", or "access denied" in `strings.xml`. The only app-created child-visible surfaces are the neutral notification and the brief "Play time has started." confirmation |

## Pending (device and build)

| Check | How |
| --- | --- |
| Release classpath | `.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath`; confirm no analytics/ads/crash libraries arrive transitively |
| Backup exclusion on device | `adb shell bmgr backupnow com.kidsgarden.app` and a device-to-device transfer; confirm the settings file is not restored |
| No output at runtime | `adb logcat --pid=<app pid>` through setup, sessions, a call, VPN revoke, and engine failure; expect nothing from the app or native library |
| Storage tests | Run `StoragePrivacyTest` on API 35 and 36 |
| Native packaging | Confirm `libkg-tunnel.so` loads on 4 KB and 16 KB page devices |

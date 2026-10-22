# Research: Kids Garden V1

**Date**: 2026-10-10 (updated after clarification session: telephony-only call pause, default SMS exclusion, English-only, memory-only authorization)
**Inputs**: [spec.md](spec.md), the planning request, and its additional event/UI/testing phases.
**Result**: No open NEEDS CLARIFICATION items. Only normal mobile/Telecom calls pause child play; WhatsApp and other app calls never pause but `com.whatsapp` is excluded from dulling. Phase 1 and Phase 2 can start. Implementation/device evidence and release gates remain pending.

## R1 — Scoped V1 call handling (telephony only)

**Decision:** Pause child play only for cellular/IMS calls (including system Wi-Fi calling) observed through `TelephonyCallback.CallStateListener`, registered for each active subscription, with READ_PHONE_STATE requested after a parent-facing explanation. Map OFFHOOK to ACTIVE; RINGING and IDLE to INACTIVE. Do not use `TelecomManager.isInCall()`, the deprecated device-wide `TelephonyManager.getCallState()`, or `AudioManager` mode for pausing. WhatsApp and every other third-party call never pause play time in V1.

**Evidence:** `TelephonyCallback.CallStateListener` reports call state for the subscription it is registered on and requires READ_PHONE_STATE. [Callback reference](https://developer.android.com/reference/android/telephony/TelephonyCallback.CallStateListener), [call-state constants](https://developer.android.com/reference/android/telephony/TelephonyManager#CALL_STATE_OFFHOOK)

`TelecomManager.isInCall()` also counts calls registered by self-managed `ConnectionService` apps, so it can be true during a third-party call. [TelecomManager](https://developer.android.com/reference/android/telecom/TelecomManager#isInCall())

`AudioManager` modes (`MODE_IN_COMMUNICATION`) are set by any VoIP or voice-chat app, including games, and do not identify the app. [AudioManager](https://developer.android.com/reference/android/media/AudioManager)

**Rationale:** Per-subscription telephony state is the only public, permission-scoped signal that cannot be triggered by a third-party app. Game voice chat therefore cannot freeze the timer (Constitution I: no unlimited-play loophole). App-identifying alternatives (notification listener, Accessibility, Usage Access, default-dialer role) are prohibited by the spec.

**Device verification (AFC-007):** On API 35/36 reference devices confirm (a) cellular and Wi-Fi calls report OFFHOOK on the registered subscription, (b) a WhatsApp call and a game voice chat do not, (c) dual-SIM devices report each subscription, (d) subscription add/remove re-registers listeners. If (b) fails on a device, the telephony source is reported UNAVAILABLE there and countdown continues.

**Observation limits:** OFFHOOK covers dialing, active, and held calls; that is acceptable coarse "in a phone call" state. Ringing alone never pauses. Preserve the pre-pause next session boundary as a mandatory fresh query so a lost end callback cannot leave cached ACTIVE state indefinitely. Query failure or unavailable/inactive state resumes countdown; a fresh active result establishes only the next projected boundary checkpoint. See [CallStateMonitor contract](contracts/call-state-monitor.md).

**Privacy and copy:** No call logs, numbers, SIM identifiers, call history/metadata, messages, notification content, contacts, or traffic inspection. Missing/unavailable detection shows only this parent-facing limitation: “Play time pauses during normal phone calls. Calls in other apps, such as WhatsApp, are not slowed down but do not pause play time.”

**Alternatives considered:** Generic audio-mode pause with or without a cap (rejected in clarification: cannot distinguish WhatsApp from game voice chat); `TelecomManager.isInCall()` (includes self-managed third-party calls); notification/Accessibility/Usage Access (prohibited); default-dialer role (changes the product). V2 carries third-party call pause under a privacy-compatible app-identification approach.

## R2 — Local VPN forwarding, not merely a service skeleton

**Decision:** Put forwarding behind `ForwardingEngine`. For the isolated engineering spike, evaluate `heiher/hev-socks5-tunnel` with an embedded, loopback-only local SOCKS relay. Forwarding/privacy qualification remains required.

**Evidence:** VpnService exposes a packet interface, with user consent and protected egress sockets; it is not a local TCP/IP implementation. [VPN guide](https://developer.android.com/develop/connectivity/vpn)

The tunnel upstream documents Android, dual stack, TCP/UDP, and MIT licensing. The paired local server supports TCP/UDP relay and Android builds. [Tunnel repository](https://github.com/heiher/hev-socks5-tunnel), [server README](https://raw.githubusercontent.com/heiher/hev-socks5-server/main/README.md)

**Rationale:** A tested user-space stack is preferable to inventing TCP retransmission, fragmentation, UDP mapping, and IPv6 support. A remote SOCKS server is prohibited; an embedded local relay is an implementation detail, not a product backend.

**Conditions before production selection:** Pin source/submodule hashes, review transitive licenses, add socket-protection and runtime shaping hooks, disable DNS mapping, audit all diagnostics, measure flow/queue limits and Normal overhead.

**Recorded evidence (2026-10-11, T040/T093):** Source audited and pinned to upstream commit `07bea57` with submodule hashes and licenses (MIT, lwIP BSD-3-Clause) in [forwarding-engine-selection.md](evidence/forwarding-engine-selection.md). Socket protection and shaping live in the Kotlin relay; mapped DNS is never configured; the config is passed in memory. Since then the submodule is in the repository and the native library builds (2026-10-11). Still unmet: it has not run on a device, and flow/queue limits and Normal overhead are unmeasured ([vpn-feasibility.md](evidence/vpn-feasibility.md), status NOT RUN).

**Alternatives considered:** Reading/sleeping/dropping TUN packets fails connectivity. A home-grown full stack exceeds V1 scope. Go/gVisor tun2socks adds embedding/runtime work and its listed primary platforms do not establish Android readiness. [Alternative upstream](https://github.com/xjasonlyu/tun2socks)

## R3 — Native privacy, packaging, and packet policy

**Decision:** Treat every native dependency as subject to the same no-logs/no-content rule. Disable/remove mapped DNS, name-based proxying, packet capture, traffic metrics export, and direct stdout/stderr output, including error branches.

**Evidence:** The tunnel has configurable logger output, but configuration errors also print directly to stderr; turning logging “off” is not alone sufficient. [Logger](https://raw.githubusercontent.com/heiher/hev-socks5-tunnel/main/src/misc/hev-logger.c), [configuration code](https://raw.githubusercontent.com/heiher/hev-socks5-tunnel/main/src/hev-config.c), [mapdns configuration](https://raw.githubusercontent.com/heiher/hev-socks5-tunnel/main/conf/main.yml)

**Recorded evidence (2026-10-11, T086/T093):** The upstream logger only writes when `log-file` is set, which Kids Garden never does; config and stack-overflow paths still call `fprintf(stderr)`, so the JNI bridge redirects stdout and stderr to `/dev/null` at load. `hev-debugger.c` compiles only with `ENABLE_DEBUG`, which is not defined. Runtime confirmation on a device is pending ([privacy-audit.md](evidence/privacy-audit.md)).

**Rationale:** No app-generated debug logs means native error paths cannot be exceptions. Temporary numeric IP/port mappings and opaque buffers are necessary for forwarding, not retained destination history.

Build only the required arm64-v8a and x86_64 ABIs initially. Validate native packaging on 4 KB and 16 KB page-size environments; upstream flags are not proof that the final APK works. [Android page sizes](https://developer.android.com/guide/practices/page-sizes), [upstream Android build](https://raw.githubusercontent.com/heiher/hev-socks5-tunnel/main/Android.mk)

**Alternatives considered:** Error-only logging or retaining debug packet dumps contradict the request. Remote proxies contradict local-only design.

## R4 — Foreground lifecycle and permissions

**Decision:** A configured VPN uses the eligible `systemExempted` foreground service type, with base/type permissions and a neutral foreground notification. Start from visible parent setup; recheck consent at each attempt. Validate eligibility/start order on API 35/36; catch failures as unavailable capability. POST_NOTIFICATIONS and READ_PHONE_STATE each get a parent-facing explanation screen before the system prompt (Constitution V).

**Evidence:** Configured VPN apps are among the documented eligible uses; the type is not a general background-execution exemption. Android 15/16 restrictions still apply. [Service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [background starts](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [service changes](https://developer.android.com/develop/background-work/services/fgs/changes)

**Rationale:** Do not misuse dataSync, phoneCall, device admin, exact-alarm access, or battery exemptions to keep an unrelated service alive. Denial of POST_NOTIFICATIONS does not justify claiming all system foreground indications are hidden. Denial of READ_PHONE_STATE only disables call pause.

Boot/update execution starts at DULL with no grant (authorization is memory-only, R7), or at DEACTIVATED without starting the VPN when the parent has saved Deactivated (FR-031). The service may recover only when Android permits; no pre-unlock/force-stop enforcement promise. Initial distribution does not require always-on or lockdown; opt out of always-on support until validated rather than advertise it accidentally.

**Alternatives considered:** `specialUse` is not an automatic fallback for failed eligibility; it needs a valid documented use/declaration. A persisted VPN flag is not consent or live capability.

## R5 — Screen, lock, and time events

**Decision:** Runtime-register screen/user-present receivers while the process/service is active; sample interactive/display/keyguard state on startup, resume, system events and before relaxing a profile. Any user-present event discards surviving Parent Mode defensively. Do not use a generic lock listener that the app cannot obtain.

**Evidence:** SCREEN_OFF is a runtime broadcast about interactive state, not necessarily physical display state. Direct keyguard listeners require a permission restricted to privileged/specific-role apps. [Intent reference](https://developer.android.com/reference/android/content/Intent#ACTION_SCREEN_OFF), [KeyguardManager](https://developer.android.com/reference/android/app/KeyguardManager), [restricted permission](https://developer.android.com/reference/android/Manifest.permission#SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE)

**Rationale:** Parent screen-off/lock rules remain, including during a call. Physical display-off/proximity versus noninteractive events and screen-on lock must be explicitly qualified; do not quietly exempt calls from revocation. Exact instantaneous observation of every OEM lock path is not established.

**Gate G-LOCK:** Open device-qualification gate. Supported pathways must revoke on observation within the spec bound and never survive unlock. Unsupported observation cannot be presented as full OS control.

**Time decision:** Use `SystemClock.elapsedRealtime` through `TimeProvider` for all elapsed duration, including sleep. Epoch timestamps are display/projection values only and are never persisted. Only detectable active mobile/Telecom calls freeze play; lock alone does not. [SystemClock](https://developer.android.com/reference/android/os/SystemClock)

**Scheduling decision:** The next boundary is scheduled by `ServiceBoundaryScheduler` inside the foreground VPN service as a one-shot delay against an elapsed-realtime deadline. Because in-process delays may not advance during deep sleep, `ForwardingEngine` compares `elapsedRealtime()` to the current policy deadline before forwarding under a Normal policy, and screen-on/user-present events trigger re-evaluation. A visible dashboard may tick for display only. No exact alarms, WorkManager loops, or wake locks.

## R6 — Build and dependency baseline

**Decision:** Android Studio project at repository root; AGP 8.11.1, Gradle 8.13, JDK 17, Kotlin 2.2.21; minSdk 35, compileSdk/targetSdk 36. Use matching Kotlin Compose/serialization compiler plugins, Kotlin DSL, and a version catalog.

**Rationale:** This conservative toolchain lies within the documented AGP/Kotlin compatibility overlap. [AGP 8.11](https://developer.android.com/build/releases/agp-8-11-0-release-notes), [Kotlin compatibility](https://kotlinlang.org/docs/gradle-configure-project.html)

Use DataStore 1.2.1 and kotlinx-serialization-json 1.9.0. Stable Compose UI/Material3, Activity Compose, Navigation Compose, lifecycle/ViewModel, coroutines and tests are catalog dependencies; Phase 1 must resolve exact compatible artifact versions and freeze them after compilation. No dynamic versions or automatic newest-version upgrades. Compose's runtime BOM is distinct from the Kotlin-matched compiler. [Compose BOM](https://developer.android.com/develop/ui/compose/bom), [Compose/Kotlin guidance](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)

**Alternatives considered:** AGP 9 introduces a different Kotlin integration path unnecessary here. Remotion has no role in this Kotlin/Compose Android product; no React/video runtime is added.

## R7 — DataStore JSON, memory-only authorization, and corruption

**Decision:** One immutable settings model, one app-scoped typed DataStore, one process for activity and VPN, one repository using `updateData`. JSON in `noBackupFilesDir`. The document holds only parent settings, the PIN credential, and setup flags. Authorization mode, child-session timing, pause accounting, and parent-interface access live only in `SessionManager` memory.

**Evidence:** DataStore supports custom serializers, transactional updates and corruption replacement; it must not have competing instances for the same file. [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)

**Rationale:** FR-027 discards every grant on restart, so persisted mode/session data could never be used. Removing it eliminates the grant write-then-publish ordering, stale-acknowledgment handling for grants, and per-pause disk writes (Constitution VII). A new process always starts at DULL by construction.

**Decoding rule:** Inspect `schemaVersion` before decoding; reject unsupported versions and malformed credentials, then reset safely per spec. Unknown keys are corruption, except the two legacy keys `mode` and `childSession`, which are explicitly ignored and dropped on the next write (FR-026). Schema migrations are deterministic/idempotent, cannot recursively call DataStore, and never restore authorization. [DataMigration](https://developer.android.com/reference/androidx/datastore/core/DataMigration), [JSON handling](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-builder/ignore-unknown-keys.html)

Ordinary I/O failure on a settings write is reported to the parent and does not change authorization; a failed PIN-change write leaves the old credential valid.

**Alternatives considered:** Persisting mode/session for a restart notice (rejected in clarification); Room/SQLite, multiple process stores, permissive coercion of unknown fields, and timer writes.

## R8 — PIN credential and backup

**Decision:** Six ASCII digits; PBKDF2WithHmacSHA256, 600,000 iterations, reduced on 2026-10-21 by the owner to 180,000 iterations (credential v2) for a faster unlock, with v1 credentials re-hashed on the next correct PIN; 16 random salt bytes and a 32-byte verifier. Persist supported parameters/version, salt, and verifier; validate their sizes and work factor before hashing. Off-main derivation, fixed-length digest comparison, best-effort temporary-array clearing, no saved-state PIN, no recovery secret. Volatile five-failure/30-second delay remains.

**Evidence:** PBKDF2 SHA256 is Android-available for the target platforms; the work factor follows OWASP's PBKDF2 guidance. [SecretKeyFactory](https://developer.android.com/reference/javax/crypto/SecretKeyFactory), [OWASP password storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html), [MessageDigest](https://developer.android.com/reference/java/security/MessageDigest)

**Rationale:** A hardened salted credential meets V1 without adding native hashing dependencies. Benchmark the weakest supported device; do not lower security silently to meet latency. A PIN of one to six digits is low entropy if local data are extracted, and a one-digit PIN can be guessed in at most ten tries (two retry delays); the owner accepted this for this version on 2026-10-12.

**Backup decision:** `noBackupFilesDir` plus `allowBackup=false` and cloud/device-transfer exclusions. Validate OEM behavior. [Android backup](https://developer.android.com/identity/data/autobackup)

**Alternatives considered:** Raw PIN, fast unsalted digest, cloud recovery, or debug logging are excluded. Keystore pepper is a future defense-in-depth option.

## R9 — Requirements reconciled

**Decision:** Valid Parent Mode continues through calls subject to lock/screen-off revocation; only detectable active mobile/Telecom calls freeze child play/ramp time. DULL remains for non-excluded traffic and Soft Landing retains its frozen policy. End/loss of valid detection resumes the same remainder silently. Child start immediately clears parent-interface authorization.

Locking never pauses the timer; validated telephony call time is the only exception. Treat the user's JSON as illustrative: MEDIUM resolves to 128 kbps/600 ms, not the STRONG 64/800 values shown in that example.

**Soft Landing:** staged interpolation to the chosen target in three stages (1/3, 2/3, final), never a path through profiles stronger than that target. Gentle cannot pass through Medium.

## R10 — Communication-app exclusions and package visibility

**Decision:** Before `establish()`, resolve and exclude with `VpnService.Builder.addDisallowedApplication`:

- the default dialer (`TelecomManager.getDefaultDialerPackage()`) and system dialer (`getSystemDialerPackage()`);
- the current default SMS/RCS app (`Telephony.Sms.getDefaultSmsPackage(context)`);
- `com.whatsapp`.

Resolve these every time the VPN is established; never persist the results. Declare `<queries>` in the manifest for `com.whatsapp` and for `ACTION_DIAL` and `ACTION_SENDTO` (`smsto:`) intents.

**Evidence:** Since Android 11, package visibility filtering hides other apps unless declared in `<queries>`, and `addDisallowedApplication` throws `NameNotFoundException` for packages the app cannot see. [Package visibility](https://developer.android.com/training/package-visibility), [addDisallowedApplication](https://developer.android.com/reference/android/net/VpnService.Builder#addDisallowedApplication(java.lang.String)), [getDefaultSmsPackage](https://developer.android.com/reference/android/provider/Telephony.Sms#getDefaultSmsPackage(android.content.Context)), [getDefaultDialerPackage](https://developer.android.com/reference/android/telecom/TelecomManager#getDefaultDialerPackage())

**Rationale:** Narrow `<queries>` entries avoid QUERY_ALL_PACKAGES and broad app inventory. Each package is excluded independently; `NameNotFoundException` for one (for example WhatsApp not installed) skips only that entry. A change of default SMS app takes effect at the next VPN establish; no broadcast is available to third-party apps for that change, and rebuilding on a timer would be polling.

**Alternatives considered:** QUERY_ALL_PACKAGES (broad inventory, Play policy restricted); hard-coded SMS package names (wrong on many OEMs); excluding WhatsApp Business `com.whatsapp.w4b` (not in spec; V2 allowlist).

## R11 — English-only text

**Decision:** All user-visible text lives in `app/src/main/res/values/strings.xml`. Enable lint `HardcodedText` as an error for XML resources (manifest labels, notification channels). Android lint does not flag string literals in Compose, so Compose UI takes text from `stringResource`, enforced by code review and the quickstart literal search. No translations or RTL layout work in V1.

**Rationale:** Keeps V1 small while making later localization a translation-only change (NFR-008). The exact required strings (wrong-PIN, call limitation, “Kids Garden is running”) become single resource entries that tests assert against.

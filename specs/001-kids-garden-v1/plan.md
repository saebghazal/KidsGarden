# Implementation Plan: Kids Garden V1

**Branch**: `001-kids-garden-v1` (Spec Kit feature identity; actual Git branch remains `master`) | **Date**: 2026-10-11 | **Spec**: [spec.md](spec.md)

**Input**: `specs/001-kids-garden-v1/spec.md` (including clarification session 2026-10-10) plus the user's Kotlin/Compose architecture, UI, event, testing and 13-phase implementation request.

**Status** (updated 2026-10-11): Code and tests for Phases 1–8 are written on branch `001-kids-garden-v1` (86 of 94 tasks). The first local build on Windows passes: domain tests 117/117, app JVM tests 75/75, lint with no errors, instrumented sources compile, and the debug APK builds with the native tunnel. Nothing has run on a device, so every gate below remains OPEN.

## Summary

Build a free, local-only Android 15/16 app using Kotlin, Compose, typed DataStore JSON for parent settings, secure parent PIN verification, and one pure domain state machine that holds authorization in memory. DULL is the default authorization; Parent Mode ends on screen off/lock; child grants last 10/20/30 minutes of play time with a 1/2-minute Soft Landing. Only detectable active mobile/Telecom calls freeze child play/ramp time; lock alone never pauses play. Starting child play immediately clears access to parent controls.

A local `VpnService` forwards and shapes traffic. The default/system dialer, the default SMS/RCS app and `com.whatsapp` are excluded from the VPN so calls and messages are never slowed; those exclusions never pause play time. WhatsApp and other third-party calls do not pause the timer, because no permitted public API can tell them apart from game voice chat ([research R1](research.md#r1--scoped-v1-call-handling-telephony-only)). Missing phone-state permission does not block sessions or working dulling.

No backend, Room/SQLite, cloud account, analytics, app-created logs, content collection, DPC, factory reset or Remotion runtime.

## Technical Context

**Language/Version**: Kotlin 2.2.21; JDK 17; Gradle Kotlin DSL; AGP 8.11.1 and Gradle 8.13.

**Primary Dependencies**: Jetpack Compose UI/Material3, Activity Compose, Navigation Compose, lifecycle/ViewModel and StateFlow; coroutines; DataStore 1.2.1; kotlinx-serialization-json 1.9.0; Kotlin-matched Compose/serialization compiler plugins. Freeze remaining stable AndroidX/test versions in the Phase 1 catalog after resolving against this toolchain. No dynamic versions. Manual application dependency container; no DI framework.

**Storage**: One app-private JSON settings document in `noBackupFilesDir` (PIN credential, intensity, profile map, Soft Landing duration, VPN preference, setup flags), one DataStore instance/process/repository. Authorization mode, child-session timing, pause accounting and parent-interface access are memory-only. No database or backup/export.

**Testing**: JVM JUnit and kotlinx-coroutines-test for domain/data/security/profile logic; AndroidX test runner, Compose UI tests, real API 35/36 phones (including one dual-SIM device), external synthetic traffic/call lab fixtures. Synthetic reports are outside the installed product; product logging stays disabled in debug and release.

**Target Platform**: Android phones, minSdk 35, compileSdk 36, targetSdk 36; Android Studio. arm64-v8a physical devices and x86_64 emulators if the native spike proceeds; 4 KB/16 KB native qualification.

**Project Type**: Installed mobile app, one Android app module plus a small pure Kotlin domain module. Optional audited native forwarding adapter is conditional, not an approved dependency yet.

**Language/Localization**: English only; all user-visible text in `res/values/strings.xml` ([research R11](research.md#r11--english-only-text)).

**Performance Goals**: Visible action/state reaction within 1 second while executing; Normal at least 90% of baseline median throughput and at most 50 ms added median RTT; no more than two battery percentage points over a matched two-hour screen-off baseline. PIN hashing benchmark must fit parent-action UX without weakened credentials.

**Constraints**: No child confrontation; no unauthorized normal-use grant; no logs/content/remote services; no promise of protection after force-stop/uninstall/revocation. Communication-app exclusions are best-effort per package and never pause play. Call detection never disables device dulling. User-visible SETUP_REQUIRED is separate from actual authorization. Native stack and Android lifecycle must be qualified, not inferred from service startup.

**Scale/Scope**: One parent PIN, one current authorization, one device/profile, ten requested parent screens, three play durations, three dull profiles. Bounded volatile packet queues/flow state; no usage history or per-app reports.

## Constitution Check

Evaluated against constitution v2.0.0, before research and again after Phase 1 design.

| Principle / gate | Pre-research disposition | Post-design result |
| --- | --- | --- |
| I — Unauthorized means DULL | One reducer; memory-only authorization | PASS; every process start is DULL by construction, call pause/exclusion is not a grant |
| I — Explicit authorization | PIN plus explicit action for Parent Mode or Child Play Session | PASS; lock/screen-off revokes Parent Mode |
| I — Scoped call pause / no loophole | Only per-subscription telephony state pauses | PASS; WhatsApp is "unsupported" under I (countdown continues), game voice chat cannot pause |
| II — Quiet transitions / essential use | Silent pauses; dialer, SMS/RCS and WhatsApp excluded from dulling | PASS; device qualification pending, no universal guarantee |
| III — Privacy | Allowlist settings only, no inspection/history, salted verifier | PASS; persists less than III allows (no session state) |
| III — No app-created logs | Debug and release | PASS; external synthetic test evidence only |
| IV — Central testable state machine | Pure Kotlin domain, injected time, one event owner | PASS; tests mandatory before acceptance |
| V — Honest Android compliance | Public APIs, explanation before VPN/notification/phone-state prompts, narrow `<queries>` | PASS; G-LOCK/G-LIFECYCLE qualification pending |
| VI — Local/free | JSON only; no product remote connection, ads, database | PASS; local embedded relay is not a remote backend |
| VII — Simplicity/battery | Two modules, manual wiring, event-driven checks, no grant writes | PASS with native-forwarding exception; measurements pending |

No violation requires justification. One wording note: Principle I and the V1 scope still describe "best-effort WhatsApp calls" pausing the timer. The spec is stricter (WhatsApp never pauses), which satisfies I because unsupported calls must leave countdown running. A PATCH amendment through `/speckit-constitution` would remove the misleading wording; it does not block planning.

### Gate register

Updated 2026-10-11. "Written" means code and tests exist. JVM tests have run locally (see G-QUALITY); nothing has run on a device.

| Gate | Status | Required evidence / effect | Recorded evidence | Unmet |
| --- | --- | --- | --- | --- |
| Scoped call acceptance | OPEN qualification | Telephony pause on single and dual SIM; WhatsApp/game voice do not pause; communication exclusions where possible; permission fallback/countdown/privacy. | Written: per-subscription `TelephonyCallStateMonitor`, pure aggregator, reducer pause rules with mandatory checkpoint; `CallPauseTest`, `CallObservationRecoveryTest`, `TelephonyStateAggregatorTest`; `CommunicationAppExclusions` with `CommunicationAppExclusionsTest`. | All tests unrun. Physical single/dual-SIM calls, WhatsApp and game voice not observed (T089). |
| G-LOCK | OPEN qualification | Screen noninteractive, physical display-off, keyguard and unlock behavior on API 35/36; no Parent Mode survives lock/unlock. Unsupported paths cannot be claimed protected. | Written: runtime SCREEN_OFF/USER_PRESENT receivers, sampled `isInteractive`/`isDeviceLocked`; `ParentModeTest` covers every revocation path and lock-before-start. | Broadcast delivery and keyguard paths not observed on API 35/36 (T088). |
| G-VPN | OPEN prototype | Actual local TCP/UDP/IPv4/IPv6 forwarding, protected sockets, reconnect, bounded memory; not a TUN skeleton. | Engine audited and pinned (`evidence/forwarding-engine-selection.md`); written: JNI bridge, loopback relay, shaping scheduler with deadline guard, controller reporting RUNNING only after a forwarding probe; `FailSafePolicyTest`, `VpnPolicyMapperTest`. | Submodule pinned and `libkg-tunnel.so` builds (16 KB aligned, JNI entry points exported); it has not run. Forwarding qualification not run (`evidence/vpn-feasibility.md`, T050). |
| G-LIFECYCLE | OPEN qualification | Foreground type/consent eligibility, revoke, boot, process restart, user stop, second VPN; no startup promises without running service. | Written: systemExempted foreground service, `onRevoke`/failure shutdown that fails open, direct VPN-loss reporting, boot/update recovery gated on the saved preference and live consent; `RecoveryTest`. | Foreground eligibility, revoke, second VPN, boot recovery not observed on devices (T088). |
| G-PRIVACY | OPEN audit | Kotlin/native error paths produce no app logs or retained content/destinations; backup exclusions; source/license audit; settings file contains no authorization/session fields. | Source and manifest audit clean (`evidence/privacy-audit.md`); written: `StoragePrivacyTest`; native stdout/stderr silenced. | Release classpath report, on-device backup/transfer check, runtime logcat check, and native packaging check not run (T086). |
| G-QUALITY | OPEN validation | Unit/UI/manual suite and spec performance, battery, accessibility, English string-resource checks. | Written: domain, JVM and instrumented suites for every story, `AccessibilityTest`; all copy in `strings.xml` with no literal UI text found by search. Run locally 2026-10-11: `:domain:test` 117/117, `:app:testDebugUnitTest` 75/75, `:app:lintDebug` 0 errors, instrumented sources compile, `:app:assembleDebug` builds. | Instrumented suite not run on a device (T092). Performance, battery (T090), and user studies (T091) not done. |

## Project Structure

### Documentation (this feature)

```text
specs/001-kids-garden-v1/
  spec.md
  plan.md
  research.md
  data-model.md
  quickstart.md
  contracts/
    call-state-monitor.md
    state-machine.md
    android-vpn.md
    parent-ui.md
    settings-repository.md
    session-manager.md
    parent-pin-manager.md
    vpn-dulling-controller.md
  checklists/requirements.md
  tasks.md                         # executable checklist (generated by /speckit-tasks)
```

### Source Code (repository root; proposed, not generated)

```text
settings.gradle.kts
build.gradle.kts
gradle.properties
gradlew / gradlew.bat
gradle/wrapper/
gradle/libs.versions.toml
domain/
  build.gradle.kts
  src/main/kotlin/com/kidsgarden/app/domain/
    Mode.kt, StateMachine.kt, SessionManager.kt, SetupPolicy.kt
    DullModePolicy.kt, SoftLandingPolicy.kt, TimeProvider.kt, DomainEvent.kt
    AuthorizationState.kt, ChildGrant.kt, CallState.kt, ProtectionStatus.kt, Ports.kt
  src/test/kotlin/com/kidsgarden/app/domain/
app/
  build.gradle.kts
  src/main/AndroidManifest.xml     # includes narrow <queries> for exclusions
  src/main/java/com/kidsgarden/app/
    KidsGardenApplication.kt, AppContainer.kt, MainActivity.kt
    ui/
      navigation/                  # KidsGardenNavHost.kt, ParentAccessGuard.kt
      setup/                       # welcome, privacy, create PIN, VPN, notification and phone-state explanations
      pin/, dashboard/, session/, settings/
    data/
      SettingsDocument.kt, SettingsDocumentSerializer.kt
      SettingsRepository.kt, DataStoreProvider.kt, SettingsMigrations.kt
    security/
      ParentPinManager.kt, PinHasher.kt
    vpn/
      KidsGardenVpnService.kt, VpnDullingController.kt
      ThrottleProfile.kt, VpnState.kt, ForwardingEngine.kt
      TrafficShapingScheduler.kt, ServiceBoundaryScheduler.kt
      CommunicationAppExclusions.kt
    receivers/
      UserPresentReceiver.kt, ScreenStateReceiver.kt, BootReceiver.kt
      DeviceEventCoordinator.kt
    permissions/
      PermissionStatusProvider.kt, AndroidSetupStatusProvider.kt
      VpnPermissionLauncher.kt, NotificationPermissionLauncher.kt, PhoneStatePermissionLauncher.kt
    platform/time/
      AndroidTimeProvider.kt
    calls/
      TelephonyCallStateMonitor.kt
  src/main/res/values/strings.xml
  src/main/res/xml/data_extraction_rules.xml
  src/main/res/xml/backup_rules.xml
  src/main/cpp/                    # only if native candidate passes selection gate
  src/test/
  src/androidTest/
```

**Structure Decision**: Android adapters live in the requested packages and together form the constitution's platform layer. A single JVM domain module enforces no Android imports; all domain code and its tests live under `domain/`. Activity and VPN share a single process and application-owned state/data instances. The settings type is named `SettingsDocument` everywhere. Existing Python/IDE files are unrelated workspace material; preserve them.

## Design

### Single state owner and effects

`StateMachine.reduce` is the only transition function: validated state + event + clock snapshot → next state + effects. `SessionManager` owns the serialized event queue, StateFlow and effect execution; it does not duplicate transition logic. `DullModePolicy`/`SoftLandingPolicy` compute intent, and `VpnDullingController` applies it only when capability allows. See [state contract](contracts/state-machine.md).

A correct PIN yields a volatile parent-interface token, not a phone grant. Settings require that token. New phone grants require a fresh PIN-derived one-use grant permission plus explicit action. Child start commits the session and clears both tokens before confirmation.

Authorization is memory-only, so a grant commits in one reducer step: verify the grant permission, interactive/unlocked state and live capability, bump the generation, publish Normal. There is no grant write or pending-grant state. Revocation immediately invalidates the generation; the shaping path checks the current policy/generation/deadline before forwarding under a prior Normal grant. A new process always starts at DULL.

### Clocks and call preservation

`elapsedRealtime` (via `TimeProvider`) is the only time authority. Epoch values are computed in memory for parent display and never persisted. Consume elapsed play only when no call pause exists; overlapping or multi-SIM calls form a single paused interval.

Only the aggregated per-subscription telephony state pauses child/ramp accounting, with no network override: non-excluded traffic keeps DULL or the frozen Soft Landing stage. End or unavailable detection resumes the remaining time; permission loss cannot leave a stale pause. Lock/screen-off still revokes Parent Mode; restart/invalid timing revokes grants. 30-minute grant − 5 minutes play + 25-minute phone call → 25 minutes remain.

### Boundary scheduling

`ServiceBoundaryScheduler` (app, inside the foreground VPN service) schedules only the next play/ramp/expiry boundary as a one-shot delay against an elapsed-realtime deadline, implementing the domain `BoundaryScheduler` port. Delays may not advance in deep sleep, so `ForwardingEngine` compares `elapsedRealtime()` to the policy deadline before forwarding under Normal, and screen-on/user-present re-evaluate state. No exact alarms, WorkManager loops or wake locks ([research R5](research.md#r5--screen-lock-and-time-events)).

### Profile application

NORMAL: unlimited intentional rate, 0 ms delay. GENTLE: 256 kbps / 300 ms added RTT. MEDIUM: 128 / 600. STRONG: 64 / 1000 initially, within the requested 800–1200 range. Caps are aggregate per direction, decimal kbps; added RTT is split across directions.

Soft Landing uses three target-relative stages at 1/3, 2/3 and final progress, beginning with Normal. Interpolate reciprocal bandwidth and latency only toward the session's captured target; no stage is more severe than that target, so Gentle never passes through Medium. Pause saves ramp progress; resume continues it. A transition to authorized Normal cancels artificial delay debt without rebuilding the VPN or breaking flows.

### Persistence and credential

[data-model.md](data-model.md) defines `SettingsDocument` v1: credential, intensity, profile map, Soft Landing duration, VPN preference and setup flags only. Unknown keys are corruption except legacy `mode`/`childSession`, which are dropped. Writes occur only for parent settings changes; a failed write is reported to the parent and leaves the previous value (including the old PIN) in effect.

PBKDF2WithHmacSHA256 parameters are versioned and bounded before use. PIN buffers and retry counters remain volatile. No raw-PIN saved state, logging, analytics, crash-report SDK, cloud backup or export.

### Android execution

Runtime receivers feed events into the same state owner. Boot/package replacement starts at DULL and only attempts permitted recovery; screen/user-present handling is not a manifest-only solution. Observe keyguard/display state using public capabilities, with G-LOCK qualification.

`TimeProvider`, `SetupPolicy` and the `BoundaryScheduler`/`CallStateSource` ports are pure `:domain` interfaces. Android adapters (`AndroidTimeProvider`, `AndroidSetupStatusProvider`, `TelephonyCallStateMonitor`, `ServiceBoundaryScheduler`) live in `:app`. No Android import is permitted in `:domain`.

Use the `systemExempted` foreground type, correct permissions, a neutral notification and private app routes. Start the VPN from visible parent consent. POST_NOTIFICATIONS and READ_PHONE_STATE each follow a parent explanation screen. All forwarding sockets bypass the TUN through `protect()`; lack of protection is failure, not a loop retry. No exact alarms, WorkManager tick loop, default-dialer role, notification listener, Accessibility service, Usage Access or QUERY_ALL_PACKAGES.

### Call detection and communication exclusions

`TelephonyCallStateMonitor` implements [CallStateMonitor](contracts/call-state-monitor.md): a `TelephonyCallback.CallStateListener` per active subscription, OFFHOOK → ACTIVE, aggregated into one source. It never uses `TelecomManager.isInCall()`, the device-wide call state, or `AudioManager` modes, because those include third-party calls. Missing permission → UNAVAILABLE → countdown continues with the parent limitation: “Play time pauses during normal phone calls. Calls in other apps, such as WhatsApp, are not slowed down but do not pause play time.”

`CommunicationAppExclusions` resolves the default dialer, system dialer, default SMS/RCS app and `com.whatsapp` before each `establish()` and calls `addDisallowedApplication` per package. Manifest `<queries>` (package `com.whatsapp`; intents `ACTION_DIAL` and `ACTION_SENDTO smsto:`) make them visible on Android 11+. A missing package is skipped individually; nothing is persisted; a changed default SMS app applies at the next establish ([research R10](research.md#r10--communication-app-exclusions-and-package-visibility)).

### Forwarding engine implementation

Select and pin an auditable local forwarding engine before integration. Implement actual TUN forwarding for TCP/UDP and IPv4/IPv6, protected egress sockets, underlying-network handover, and an authenticated loopback-only relay only if the selected stack requires it. The shaping scheduler applies generic aggregate per-direction bandwidth and latency policies with bounded fair queues and transport-appropriate backpressure. `VpnDullingController` reports RUNNING only after the engine forwards successfully and applies generation-checked Normal/Dull/Soft Landing policies without per-stage teardown.

The engine may process only packet/transport metadata required to forward and schedule opaque bytes. It must not parse payloads, DNS names, URLs, domains, messages, or application content, and must not create destination/app history, packet dumps, analytics, or native/application logs. The G-VPN prototype is built early and independently of call qualification.

### Text and language

English only. Every user-visible string, including the exact required wording (wrong PIN, call limitation, “Kids Garden is running”), is a `strings.xml` entry read through `stringResource`. Tests assert against resource IDs. No translations or RTL work in V1 ([research R11](research.md#r11--english-only-text)).

## Implementation sequence

The 13 milestones remain; `tasks.md` is the executable checklist.

| Phase | Scope / dependencies | Exit evidence |
| --- | --- | --- |
| 0 — Qualification planning (non-blocking) | Telephony/dual-SIM matrix; forwarding/lock/lifecycle lab design | Documented test plans; no universal-detection prerequisite |
| 1 — Foundation | Android Studio root project, Compose, DataStore, serialization, coroutines, lifecycle, tests; pure domain interfaces and Android adapters; strings.xml; manifest `<queries>` | Pinned catalog/wrapper; API35/36 compile/lint and empty-app tests; no Android imports in domain; no database/telemetry/logging dependencies |
| 2 — Domain | Mode, StateMachine reducer, SessionManager, injected time, effect contracts | All modes/events/boundaries including pause, duplicate callbacks, lock and stale grants deterministic |
| 3 — Persistence | Settings-only JSON, defaults, validation, legacy-key drop, migrations, repository | Serialization, corrupt/future schema, failed write, no authorization fields written |
| 4 — Parent PIN | PBKDF2, salt, fixed verifier, volatile retry gate | Correct/wrong/malformed credentials, distinct salts, no raw PIN or sensitive output |
| 5 — UI foundation | App entry points, NavHost and ParentAccessGuard, all setup/PIN/dashboard/settings routes, permission explanation screens | Every protected route gated on Back/recents/notification/deep link; accessibility; limited-capability states |
| 6 — Parent Mode | Explicit grant and Normal intent, manual exit, lock/screen revocation | No parent grant survives required events; call alone does not cancel it |
| 7 — Child play | Durations, in-memory deadlines, remainder, immediate UI lock, telephony pause | 30/5/25 example; no lock-based pause/reset; multi-SIM/overlap and replacement correct; WhatsApp no pause |
| 8 — Soft Landing | Target-relative staged policy, pause/resume, expiry | Gentle never overshoots, ramp resumes, silent DULL end |
| 9 — VPN setup/engine | Consent, FGS, forwarding prototype then audited stack, protected sockets, scheduler, boundary scheduler, communication exclusions | G-VPN/G-PRIVACY evidence; excluded packages bypass while other traffic stays dull |
| 10 — Device events | Unlock/screen/boot/update/permission/service and telephony adapters | Reconcile all events with domain; no lifecycle policy duplicated in receivers |
| 11 — Explanations | Limitations, privacy, VPN, notification and phone-state explanations | Accurate limited status; exact call limitation text; no uninstall-proof, total-control or universal-call claims |
| 12 — Verification | Unit/instrumentation/manual/performance/battery matrix | Every applicable spec criterion mapped and passed; no substituted mocks for device proof |
| 13 — Release | Dependency/privacy/signing audit; private APK first | All gates green; stable signing key kept outside repo; no store publication implied |

## Validation and delivery

Use [quickstart.md](quickstart.md) for future build/run commands and acceptance scenarios; it distinguishes planned commands from commands runnable today. JVM tests cover state, elapsed accounting, call preservation, DataStore defaults/corruption/migration, credential validation and profile math. Compose tests cover the ten requested screens, permission explanation screens and stale-route protection. Physical API 35/36 testing covers actual network, telephony, exclusion and lifecycle behavior.

Initial delivery is a signed APK shared privately or sideloaded. Internal testing is optional. Do not deploy or publish from this planning task. No app logs in any build; test reports and synthetic lab measurements live outside the product.

## Complexity Tracking

| Exception / added complexity | Why needed | Simpler alternative rejected because |
| --- | --- | --- |
| Separate JVM domain module | Enforce testable Android-free state logic | Package convention alone permits accidental framework coupling; no additional feature modules |
| Conditional C/JNI user-space stack and local relay | Real VPN forwarding requires a transport implementation | Skeleton/drop/delay is not forwarding; a custom TCP stack is more complex; a remote relay is prohibited |
| Communication-app exclusions with narrow `<queries>` | Keep calls and messages unslowed while dulling the rest of the device | A global call override would bypass dulling; QUERY_ALL_PACKAGES is broad inventory |
| Per-subscription telephony listeners | Dual-SIM phones report calls per subscription | A device-wide call-state API includes third-party calls and would open the voice-chat loophole |

Untimed Parent Mode, no debug logs, and scoped call pause are now constitution-authorized (v2.0.0) and no longer exceptions. Memory-only authorization removed the grant persistence/acknowledgment machinery that earlier versions carried.

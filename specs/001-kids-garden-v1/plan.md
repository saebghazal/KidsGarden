# Implementation Plan: Kids Garden V1

**Branch**: `001-kids-garden-v1` (Spec Kit feature identity; actual Git branch remains `master`) | **Date**: 2026-10-11 | **Spec**: [spec.md](spec.md)

**Input**: `specs/001-kids-garden-v1/spec.md` (including clarification sessions 2026-10-10, 2026-10-12, 2026-10-15 and 2026-10-18) plus the user's Kotlin/Compose architecture, UI, event, testing and 13-phase implementation request.

**Status** (updated 2026-10-11): Code and tests for Phases 1–8 are written on branch `001-kids-garden-v1` (86 of 94 tasks). The first local build on Windows passes: domain tests 117/117, app JVM tests 75/75, lint with no errors, instrumented sources compile, and the debug APK builds with the native tunnel. Nothing has run on a device, so every gate below remains OPEN.

**Update 2026-10-15**: The spec added a parent Active/Deactivated switch (FR-031) and the constitution was amended to v3.0.0 to allow it. The design is below and the work is Phase 14; no code exists for it yet.

**Update 2026-10-18**: The spec added a phone compatibility check (FR-032), background-stop guidance (FR-033), and screen and sound dulling with a gradual cycle and four method switches (FR-034–036). The constitution is now v3.1.0. Design is below; the work is Phase 15; no code exists for it yet.

**Update 2026-10-18 (analysis fixes)**: `/speckit-analyze` fixes applied. During a break the media volume cap is lifted (Kids Garden never raises volume itself) and brightness eases back to the saved level, cleared only when effects stop; constitution v3.1.1 (persistence wording); SC-010 added for screen and sound effects; “Check this phone” also offered on the dashboard when the phone is unprotected. Phase numbers: plan Phase 14 = tasks Phase 10, plan Phase 15 = tasks Phase 11. Terms: “Network dulling” = VPN dulling = `vpnDullingEnabled`.

**Update 2026-10-25**: Unlock grace (FR-037, constitution v4.0.0): an unlock without the PIN gives a short, parent-set grace that fades into DULL, then a rest period. Settings schema v3. Owner UI changes the same day: up/down arrows for every time setting; the app opens as a card over the dimmed home screen. Design below; tasks Phase 12.

**Update 2026-10-27**: Owner clarification (spec Clarifications 2026-10-27, constitution v5.0.0): (1) parent-interface access lasts until lock, screen off, child start or restart; (2) a parent-only list of apps that skip Kids Garden by bypassing the VPN, settings schema v4, and a check that Deactivate fully stops the VPN; (3) Activate and Parent Mode reset the grace window, and Parent options shows grace left or time to the next grace; (4) the grace window is fixed from the first unlock, the grace pauses while locked, and unused grace is lost when the window ends. Design below; tasks Phase 13.

## Summary

Build a free, local-only Android 15/16 app using Kotlin, Compose, typed DataStore JSON for parent settings, secure parent PIN verification, and one pure domain state machine that holds authorization in memory. DULL is the default authorization; Parent Mode ends on screen off/lock; child grants last 10–120 minutes of play time (10-minute steps) with a 1/2-minute Soft Landing. Only detectable active mobile/Telecom calls freeze child play/ramp time; lock alone never pauses play. Starting child play immediately clears access to parent controls. A PIN-protected Active/Deactivated switch lets the parent turn Kids Garden off: Deactivated ends every grant, stops the VPN and never enters DULL until the parent activates again; the choice is saved and survives restart and reboot.

A local `VpnService` forwards and shapes traffic. The default/system dialer, the default SMS/RCS app and `com.whatsapp` are excluded from the VPN so calls and messages are never slowed; those exclusions never pause play time. WhatsApp and other third-party calls do not pause the timer, because no permitted public API can tell them apart from game voice chat ([research R1](research.md#r1--scoped-v1-call-handling-telephony-only)). Missing phone-state permission does not block sessions or working dulling.

No backend, Room/SQLite, cloud account, analytics, app-created logs, content collection, DPC, factory reset or Remotion runtime.

## Technical Context

**Language/Version**: Kotlin 2.2.21; JDK 17; Gradle Kotlin DSL; AGP 8.11.1 and Gradle 8.13.

**Primary Dependencies**: Jetpack Compose UI/Material3, Activity Compose, Navigation Compose, lifecycle/ViewModel and StateFlow; coroutines; DataStore 1.2.1; kotlinx-serialization-json 1.9.0; Kotlin-matched Compose/serialization compiler plugins. Freeze remaining stable AndroidX/test versions in the Phase 1 catalog after resolving against this toolchain. No dynamic versions. Manual application dependency container; no DI framework.

**Storage**: One app-private JSON settings document in `noBackupFilesDir` (Active/Deactivated flag, screen-effect switches and timings, brightness-to-restore, PIN credential, intensity, profile map, Soft Landing duration, VPN preference, setup flags), one DataStore instance/process/repository. Authorization mode, child-session timing, pause accounting and parent-interface access are memory-only. No database or backup/export.

**Testing**: JVM JUnit and kotlinx-coroutines-test for domain/data/security/profile logic; AndroidX test runner, Compose UI tests, real API 35/36 phones (including one dual-SIM device), external synthetic traffic/call lab fixtures. Synthetic reports are outside the installed product; product logging stays disabled in debug and release.

**Target Platform**: Android phones, minSdk 35, compileSdk 36, targetSdk 36; Android Studio. arm64-v8a physical devices and x86_64 emulators if the native spike proceeds; 4 KB/16 KB native qualification.

**Project Type**: Installed mobile app, one Android app module plus a small pure Kotlin domain module. Optional audited native forwarding adapter is conditional, not an approved dependency yet.

**Language/Localization**: English only; all user-visible text in `res/values/strings.xml` ([research R11](research.md#r11--english-only-text)).

**Performance Goals**: Visible action/state reaction within 1 second while executing; Normal at least 90% of baseline median throughput and at most 50 ms added median RTT; no more than two battery percentage points over a matched two-hour screen-off baseline. PIN hashing benchmark must fit parent-action UX without weakened credentials.

**Constraints**: No child confrontation; no unauthorized normal-use grant; no logs/content/remote services; no promise of protection after force-stop/uninstall/revocation. Communication-app exclusions are best-effort per package and never pause play. Call detection never disables device dulling. User-visible SETUP_REQUIRED is separate from actual authorization. Native stack and Android lifecycle must be qualified, not inferred from service startup.

**Scale/Scope**: One parent PIN, one current authorization, one device/profile, ten requested parent screens, play durations of 10–120 minutes, three dull profiles. Bounded volatile packet queues/flow state; no usage history or per-app reports.

## Constitution Check

Evaluated against constitution v2.0.0, before research and again after Phase 1 design. Re-evaluated against v3.0.0 on 2026-10-15 for the Active/Deactivated switch, and against v3.1.0 on 2026-10-18 for FR-032–036, and v3.1.1 (wording only) after analysis.

| Principle / gate | Pre-research disposition | Post-design result |
| --- | --- | --- |
| I — Unauthorized means DULL | One reducer; memory-only authorization | PASS; every process start is DULL while Active (DEACTIVATED only from a valid saved Deactivated flag), call pause/exclusion is not a grant |
| I — Parent access and grants (v5.0.0) | PIN opens the parent interface until lock, screen off, child start or restart; each grant still needs an explicit action and a one-use permission | PASS; no grant without the PIN-opened interface, unlock never restores Parent Mode |
| I — Unlock grace (v4.0.0, revised v5.0.0) | Parent-enabled, windowed, pauses while locked, reset by Activate and Parent Mode | PASS; grace is an in-memory grant, only the window start is stored, no child-facing message |
| I — Apps that skip Kids Garden (v5.0.0) | Parent-only bypass list; no VPN-off control; Deactivate fully stops the VPN | PASS; list behind PIN, stored as parent setting, launcher-intent visibility only |
| I — Active/Deactivated switch (v3.0.0) | Deactivate needs parent-interface access and the explicit switch action; Activate needs parent-interface access obtained with the PIN | PASS; only an authenticated parent action writes Deactivated; corrupt/missing/unmigratable settings default to Active |
| I — Explicit authorization | PIN plus explicit action for Parent Mode or Child Play Session | PASS; lock/screen-off revokes Parent Mode |
| I — Scoped call pause / no loophole | Only per-subscription telephony state pauses | PASS; WhatsApp is "unsupported" under I (countdown continues), game voice chat cannot pause |
| II — Screen and sound dulling (v3.1.0) | Gradual cycle, touch-transparent overlay, media stream only, brightness restored | PASS; no text to the child, no app blocking, calls/alarms untouched, effects stop during calls and outside DULL |
| II — Quiet transitions / essential use | Silent pauses; dialer, SMS/RCS and WhatsApp excluded from dulling | PASS; device qualification pending, no universal guarantee |
| III — Privacy | Allowlist settings only, no inspection/history, salted verifier | PASS; persists less than III allows (no session state) |
| III — No app-created logs | Debug and release | PASS; external synthetic test evidence only |
| IV — Central testable state machine | Pure Kotlin domain, injected time, one event owner | PASS; tests mandatory before acceptance |
| V — Honest Android compliance | Public APIs, explanation before VPN/notification/phone-state/overlay/modify-settings prompts; compatibility check and battery guidance are local and parent-initiated, narrow `<queries>` | PASS; G-LOCK/G-LIFECYCLE qualification pending |
| VI — Local/free | JSON only; no product remote connection, ads, database | PASS; local embedded relay is not a remote backend |
| VII — Simplicity/battery | Two modules, manual wiring, event-driven checks, no grant writes | PASS with native-forwarding exception; measurements pending |

No violation requires justification. The earlier wording note about "best-effort WhatsApp calls" in Principle I was resolved by the v3.0.0 amendment, which now says third-party calls never pause the timer.

### Gate register

Updated 2026-10-12. "Written" means code and tests exist. JVM tests have run locally, and the instrumented suite has run on one API 36 phone (see G-QUALITY).

| Gate | Status | Required evidence / effect | Recorded evidence | Unmet |
| --- | --- | --- | --- | --- |
| Scoped call acceptance | OPEN qualification | Telephony pause on single and dual SIM; WhatsApp/game voice do not pause; communication exclusions where possible; permission fallback/countdown/privacy. | Written: per-subscription `TelephonyCallStateMonitor`, pure aggregator, reducer pause rules with mandatory checkpoint; `CallPauseTest`, `CallObservationRecoveryTest`, `TelephonyStateAggregatorTest`; `CommunicationAppExclusions` with `CommunicationAppExclusionsTest`. | All tests unrun. Physical single/dual-SIM calls, WhatsApp and game voice not observed (T089). |
| G-LOCK | OPEN qualification | Screen noninteractive, physical display-off, keyguard and unlock behavior on API 35/36; no Parent Mode survives lock/unlock. Unsupported paths cannot be claimed protected. | Written: runtime SCREEN_OFF/USER_PRESENT receivers, sampled `isInteractive`/`isDeviceLocked`; `ParentModeTest` covers every revocation path and lock-before-start. | Broadcast delivery and keyguard paths not observed on API 35/36 (T088). |
| G-VPN | OPEN prototype | Actual local TCP/UDP/IPv4/IPv6 forwarding, protected sockets, reconnect, bounded memory; not a TUN skeleton. | Engine audited and pinned (`evidence/forwarding-engine-selection.md`); written: JNI bridge, loopback relay, shaping scheduler with deadline guard, controller reporting RUNNING only after a forwarding probe; `FailSafePolicyTest`, `VpnPolicyMapperTest`. | Submodule pinned and `libkg-tunnel.so` builds (16 KB aligned, JNI entry points exported); it has not run. Forwarding qualification not run (`evidence/vpn-feasibility.md`, T050). |
| G-LIFECYCLE | OPEN qualification | Foreground type/consent eligibility, revoke, boot, process restart, user stop, second VPN; no startup promises without running service. | Written: systemExempted foreground service, `onRevoke`/failure shutdown that fails open, direct VPN-loss reporting, boot/update recovery gated on the saved preference and live consent; `RecoveryTest`. | Foreground eligibility, revoke, second VPN, boot recovery not observed on devices (T088). |
| G-PRIVACY | OPEN audit | Kotlin/native error paths produce no app logs or retained content/destinations; backup exclusions; source/license audit; settings file contains no authorization/session fields. | Source and manifest audit clean (`evidence/privacy-audit.md`); written: `StoragePrivacyTest`; native stdout/stderr silenced. | Release classpath report, on-device backup/transfer check, runtime logcat check, and native packaging check not run (T086). |
| G-QUALITY | OPEN validation | Unit/UI/manual suite and spec performance, battery, accessibility, English string-resource checks. | Written: domain, JVM and instrumented suites for every story, `AccessibilityTest`; all copy in `strings.xml` with no literal UI text found by search. Run 2026-10-12: `:domain:test` 120/120, `:app:testDebugUnitTest` 76/76, `:app:lintDebug` 0 errors, `:app:connectedDebugAndroidTest` 43/43 on Galaxy A73 (API 36); see [evidence/qa-results.md](evidence/qa-results.md). | No API 35 device run; quickstart scenario table not run (T092). Performance, battery (T090), and user studies (T091) not done. |

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
    screen-effects.md
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

A correct PIN yields a volatile parent-interface token, not a phone grant. Settings require that token. New phone grants require a PIN-derived one-use grant permission plus explicit action; while parent-interface access lasts (until lock, screen off, child start or restart), a used permission is re-issued (FR-004, 2026-10-27). Child start commits the session and clears both tokens before confirmation.

Authorization is memory-only, so a grant commits in one reducer step: verify the grant permission, interactive/unlocked state and live capability, bump the generation, publish Normal. There is no grant write or pending-grant state. Revocation immediately invalidates the generation; the shaping path checks the current policy/generation/deadline before forwarding under a prior Normal grant. A new process always starts at DULL.

### Active/Deactivated switch (FR-031)

`Mode` gains `DEACTIVATED`. The reducer accepts two new events. `Deactivate` requires valid parent-interface access and a confirmed flag; from any state it clears Parent Mode, child grant, Soft Landing and pause accounting, bumps the generation and emits effects to save `active = false`, stop the VPN and stop the foreground service. `Activate` requires valid parent-interface access (the PIN entered to reach Parent options); it emits save `active = true` and start VPN, and moves to DULL (SETUP_REQUIRED presentation if capability is missing). Both commit only after the settings write succeeds; a failed write leaves the prior state and reports the error to the parent.

In DEACTIVATED the reducer ignores grant actions, lock/unlock, screen, call, boundary and VPN-loss events, so nothing re-enters DULL by accident. `SessionManager` starts a new process at DEACTIVATED when the loaded settings are valid and `active == false`, otherwise DULL. `BootReceiver`/package-replaced recovery does not start the VPN while Deactivated. With the VPN stopped there is no VPN or foreground notification; `ProtectionStatus` reports Deactivated, not SETUP_REQUIRED.

UI (owner change 2026-10-19): the switch sits at the top of Parent options and takes effect at once in both directions (no pop-up, no second PIN); while Deactivated a warning-colour card there and a banner on the PIN screen say Kids Garden is off, and Parent options shows only the switch and Settings. All new copy lives in `strings.xml`.

### Compatibility check and background-stop guidance (FR-032, FR-033)

`VpnDullingController.checkCompatibility()` runs after VPN consent in setup and on demand from Settings/SETUP_REQUIRED. It checks, in order: VPN consent (`VpnService.prepare`), a device policy blocking VPN configuration (`UserManager` restriction or `establish()` returning null/throwing), another VPN network already active (`ConnectivityManager` VPN transport not owned by Kids Garden), foreground-service start eligibility, and a local forwarding self-test through the TUN that uses no remote destination. It returns `Pass` or one `CompatibilityFailure` reason; nothing is persisted except the existing setup flags, and nothing is logged.

Background-stop detection is derived, not recorded: at process start or service loss, if Kids Garden is Active, network dulling is enabled, setup is complete, and the VPN is not running, `ProtectionStatus` carries `BackgroundStopped`. The dashboard shows the FR-033 message and a button that opens `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`. Kids Garden never requests the exemption itself.

### Screen and sound dulling (FR-034–036)

The reducer outputs an `EffectPolicy` (enabled effects, captured intensity) only when the mode is DULL, Kids Garden is Active and no telephony call is ACTIVE; otherwise `EffectPolicy.Off`. A pure `EffectCycle` in `:domain` maps elapsed time since cycle start, build-up and break lengths to a strength fraction 0…1 (rise over build-up, ~10 s ease-down, flat zero for the break, repeat; break 0 holds at 1). `ScreenEffectsController` in `:app` starts a cycle on ScreenOn while the policy is on, stops it on ScreenOff or policy Off, and drives three adapters from the fraction with a frame animator only while the screen is on (no wake locks, no alarms):

- `GreyOverlay`: one `TYPE_APPLICATION_OVERLAY` view with `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`, grey with alpha = fraction × target (target ≤ 0.8, Android's pass-through limit). Requires `SYSTEM_ALERT_WINDOW` (`Settings.canDrawOverlays`). Removed when a call is ACTIVE.
- `MediaVolumeCap`: `AudioManager` `STREAM_MUSIC` only; lowers toward the cap, and re-applies the cap if a volume change raises it while the effect runs. Never touches other streams. When the effect ends the volume is left as is (not raised).
- `BrightnessDimmer`: `Settings.System.SCREEN_BRIGHTNESS` and `SCREEN_BRIGHTNESS_MODE` via `WRITE_SETTINGS` (`Settings.System.canWrite`). Before the first change in a DULL period it saves the parent's level and auto setting to `SettingsDocument.brightnessRestore`; on effect end it restores them and clears the field. At process start a non-null `brightnessRestore` is restored first.

Missing access disables only that adapter and is reported in `ProtectionStatus.effects`. Dulling capability = VPN running OR any enabled effect working; SETUP_REQUIRED only when neither. The four method switches live in settings; the repository rejects a write that would turn all four off.

### Parent-interface access (FR-004, 2026-10-27)

`ParentAccess` is cleared only by ScreenOff, DeviceLocked (and an `InteractivityObserved(false)` sample), a child grant, or a process restart. The former `ParentUiLeft` event is removed: `MainActivity.onStop` and the navigation listener no longer dispatch it. In-app endings (Exit Parent Mode, PIN change, VPN loss) end grants through `expireToDull`, which keeps the access and issues a fresh one-use grant permission (`refreshPermission`); `UiOpened` re-arms a used permission. The PIN route forwards to Parent options while access is held.

### Unlock grace (FR-037)

The grace is an in-memory `ChildGrant` with `unlockGrace = true` (D = grace + fade, L = fade), so the Soft Landing ramp, call pause and expiry rules apply unchanged. A `GraceWindow` (start epoch, end elapsed, banked budget) lives in `AuthorizationState`. The first `UserPresent` in DULL opens the window and emits `PersistUnlockGraceStart(start)`; later unlocks in the window continue the banked budget by starting the grant shifted by the used part, which keeps the fade stage. ScreenOff/DeviceLocked bank `remainingMillis` and return to DULL. Grant boundaries are capped at the window end; at the window end a grace still before its fade jumps to the fade start and one already fading finishes. Activate, Deactivate and Parent Mode set the window to null and persist `null`. A new process derives an open window from the saved start with its budget counted as used (`UnlockGrace.windowFromSaved`); `graceWindowKnown` stops a late settings echo from reviving a cleared window. `ParentFacingSnapshot.graceLeftMillis` and `nextGraceInMillis` drive the Parent options line, inside the app only.

### Apps that skip Kids Garden (FR-038)

`SettingsDocument.bypassPackages` (schema v4, validated package names, at most 200, sorted and unique) is read by `KidsGardenVpnService` at every `establish()` and passed to `CommunicationAppExclusions.apply(target, parentBypass)`, which adds them to the fixed exclusions; Kids Garden itself is always removed. A changed list triggers `VpnDullingController.reconfigure()` → `ACTION_RECONFIGURE` → a new `establish()` that replaces the running interface, restarts forwarding on the new descriptor and closes the old one, without reporting lost capability, so grants continue. `BypassAppsScreen` lists launcher apps through a `<queries>` launcher intent only while it is open. There is no VPN-off control; Deactivate's `StopVpn` shuts the service (tunnel, forwarding, foreground notification) and `startAllowed` keeps it off.

### Clocks and call preservation

`elapsedRealtime` (via `TimeProvider`) is the only time authority. Epoch values are computed in memory for parent display and never persisted. Consume elapsed play only when no call pause exists; overlapping or multi-SIM calls form a single paused interval.

Only the aggregated per-subscription telephony state pauses child/ramp accounting, with no network override: non-excluded traffic keeps DULL or the frozen Soft Landing stage. End or unavailable detection resumes the remaining time; permission loss cannot leave a stale pause. Lock/screen-off still revokes Parent Mode; restart/invalid timing revokes grants. 30-minute grant − 5 minutes play + 25-minute phone call → 25 minutes remain.

### Boundary scheduling

`ServiceBoundaryScheduler` (app, inside the foreground VPN service) schedules only the next play/ramp/expiry boundary as a one-shot delay against an elapsed-realtime deadline, implementing the domain `BoundaryScheduler` port. Delays may not advance in deep sleep, so `ForwardingEngine` compares `elapsedRealtime()` to the policy deadline before forwarding under Normal, and screen-on/user-present re-evaluate state. No exact alarms, WorkManager loops or wake locks ([research R5](research.md#r5--screen-lock-and-time-events)).

### Profile application

NORMAL: unlimited intentional rate, 0 ms delay. GENTLE: 256 kbps / 300 ms added RTT. MEDIUM: 128 / 600. STRONG: 64 / 1000 initially, within the requested 800–1200 range. Caps are aggregate per direction, decimal kbps; added RTT is split across directions.

Soft Landing uses three target-relative stages at 1/3, 2/3 and final progress, beginning with Normal. Interpolate reciprocal bandwidth and latency only toward the session's captured target; no stage is more severe than that target, so Gentle never passes through Medium. Pause saves ramp progress; resume continues it. A transition to authorized Normal cancels artificial delay debt without rebuilding the VPN or breaking flows.

### Persistence and credential

[data-model.md](data-model.md) defines `SettingsDocument` v1: credential, intensity, profile map, Soft Landing duration, VPN preference and setup flags only. FR-031 and FR-034–036 bump it to v2 with `active` (default `true`), `screenEffects` (three switches, off by default; build-up 60–300 s, default 120; break 0–300 s, default 60) and nullable `brightnessRestore`; `vpnDullingEnabled` becomes the Network dulling switch; `SettingsMigrations` maps v1 → v2 with `active = true`, default `screenEffects` and null `brightnessRestore`, with a unit test. A missing `active` in a v2 document is corruption, which resolves to safe defaults (Active). Unknown keys are corruption except legacy `mode`/`childSession`, which are dropped. Writes occur only for parent settings changes; a failed write is reported to the parent and leaves the previous value (including the old PIN) in effect.

PBKDF2WithHmacSHA256 parameters are versioned and bounded before use. PIN buffers and retry counters remain volatile. No raw-PIN saved state, logging, analytics, crash-report SDK, cloud backup or export.

### Android execution

Runtime receivers feed events into the same state owner. Boot/package replacement starts at DULL (or DEACTIVATED from a valid saved flag) and only attempts permitted recovery, never starting the VPN while Deactivated; screen/user-present handling is not a manifest-only solution. Observe keyguard/display state using public capabilities, with G-LOCK qualification.

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
| 17 — Parent access, app bypass, grace window (2026-10-27) | Remove `ParentUiLeft`; `expireToDull`/`refreshPermission`; `GraceWindow` with lock pause, window end and reset; `PersistUnlockGraceStart(null)`; schema v4 `bypassPackages` with migration; exclusions + in-place tunnel rebuild; `BypassAppsScreen`; Parent options grace line | `UnlockGraceTest`, access tests in `ParentModeTest`/`SettingsEffectsTest`, `DeactivateStopsVpnTest`, exclusion and schema tests; device check of bypass, grace and full VPN stop |
| 16 — Unlock grace (FR-037, 2026-10-25) | `UnlockGrace`, grace grant from `UserPresent`, schema v3, Settings section, countdown | `UnlockGraceTest`, migration tests; device check |
| 15 — Compatibility, guidance, screen and sound dulling (FR-032–036, added 2026-10-18) | `checkCompatibility`, `BackgroundStopped` status and guidance button; `EffectCycle`, `EffectPolicy`, `ScreenEffectsController` with overlay, volume and brightness adapters; access explanation screens; four method switches; capability = VPN or any effect | Compatibility pass and each failure reason on API 35/36 with no outbound app traffic; `EffectCycleTest` timings; effects stop on screen off/grant/call/Deactivate; brightness restored after process death; touches pass through; call/alarm/ring volume unchanged; idle battery unchanged with screen off |
| 14 — Active/Deactivated switch (FR-031, added 2026-10-15) | `Mode.DEACTIVATED`, Activate/Deactivate events and effects; `SettingsDocument` v2 + v1→v2 migration; start-up and boot handling; Settings switch, confirmation, dashboard and parent-actions changes; VPN/FGS stop | Reducer tests for deactivate from every state and ignored events while Deactivated; migration and corruption → Active tests; failed-write test; device check that the VPN stops and stays off across reboot, and Activate returns to DULL |

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

Untimed Parent Mode, no debug logs, and scoped call pause are now constitution-authorized (v2.0.0) and no longer exceptions. The Active/Deactivated switch is constitution-authorized by v3.0.0 and adds no new module or dependency. Screen and sound dulling is authorized by v3.1.0 and adds no dependency; the overlay and modify-settings accesses are explained to the parent before each prompt. Memory-only authorization removed the grant persistence/acknowledgment machinery that earlier versions carried.

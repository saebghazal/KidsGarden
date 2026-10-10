---

description: "Task list for Kids Garden V1"
---

# Tasks: Kids Garden V1

**Input**: Design documents from `specs/001-kids-garden-v1/`

**Prerequisites**: plan.md, spec.md (incl. clarifications 2026-10-10), research.md, data-model.md, contracts/, quickstart.md; constitution v2.0.0

**Tests**: Required. Spec NFR-007 and Constitution IV mandate deterministic tests for every state, transition, boundary, default, corruption case and migration. Tests never write product logs or real user data.

**Organization**: Tasks are grouped by user story so each story can be implemented and tested independently. Regenerated 2026-10-11 after the clarification session (telephony-only call pause, default SMS/RCS exclusion, English-only, memory-only authorization) and the earlier analysis fixes.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: User story from spec.md (US1–US6)
- Every task names exact file paths

## Path Conventions

- Pure Kotlin domain module: `domain/src/main/kotlin/com/kidsgarden/app/domain/`, tests in `domain/src/test/kotlin/com/kidsgarden/app/domain/`. No Android imports anywhere in `domain/`.
- Android app module: `app/src/main/java/com/kidsgarden/app/`, JVM tests in `app/src/test/java/com/kidsgarden/app/`, instrumented tests in `app/src/androidTest/java/com/kidsgarden/app/`.
- Evidence (synthetic, outside the product): `specs/001-kids-garden-v1/evidence/`.
- `StateMachine.kt`, `SessionManager.kt` and `KidsGardenNavHost.kt` are shared integration points: tasks touching them are never [P] and run in ID order.

## Global rules for every task

- No logging of any kind (`android.util.Log`, `println`, `printStackTrace`, native stdout/stderr) in main or debug code (Constitution III, NFR-005).
- All user-visible text comes from `app/src/main/res/values/strings.xml` via `stringResource`/`getString` (NFR-008).
- Authorization mode, child-session timing, pause accounting and parent-interface access are never written to storage (FR-025).
- All transition decisions live in `StateMachine.reduce`; platform code only emits events and applies effects (Constitution IV).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Android Studio project, build, manifest and resources.

- [ ] T001 Create the root Gradle project: `settings.gradle.kts` including `:app` and `:domain`, root `build.gradle.kts`, `gradle.properties`, Gradle 8.13 wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`), and `gradle/libs.versions.toml` pinning Kotlin 2.2.21, AGP 8.11.1, DataStore 1.2.1, kotlinx-serialization-json 1.9.0, plus exact stable Compose BOM, Activity Compose, Navigation Compose, lifecycle, coroutines, JUnit, coroutines-test, AndroidX test and Compose UI-test versions (no dynamic versions)
- [ ] T002 [P] Configure `domain/build.gradle.kts` as a pure Kotlin JVM module (JDK 17 toolchain, coroutines-core, JUnit, kotlinx-coroutines-test) with no Android plugin or Android dependencies
- [ ] T003 [P] Configure `app/build.gradle.kts`: Android application plugin, Kotlin Compose and serialization plugins, `namespace`/`applicationId` `com.kidsgarden.app`, minSdk 35, compileSdk 36, targetSdk 36, JDK 17, `implementation(project(":domain"))`, Compose/Navigation/lifecycle/DataStore/serialization/coroutines dependencies, unit and instrumented test dependencies, abiFilters arm64-v8a and x86_64, and `lint { error += "HardcodedText" }`
- [ ] T004 Create `app/src/main/AndroidManifest.xml`: permissions INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SYSTEM_EXEMPTED, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, READ_PHONE_STATE; `<queries>` with `<package android:name="com.whatsapp"/>` and intent queries for `android.intent.action.DIAL` and `android.intent.action.SENDTO` with data scheme `smsto`; `android:allowBackup="false"`, `android:dataExtractionRules`, `android:fullBackupContent`; launcher `MainActivity`; `KidsGardenVpnService` with `android:permission="android.permission.BIND_VPN_SERVICE"`, `android.net.VpnService` intent filter and `android:foregroundServiceType="systemExempted"`; `BootReceiver` for BOOT_COMPLETED and MY_PACKAGE_REPLACED. No QUERY_ALL_PACKAGES, call-log, contacts, SMS read/send, accessibility, notification-listener, usage-access or device-admin entries
- [ ] T005 [P] Create `app/src/main/res/xml/data_extraction_rules.xml` and `app/src/main/res/xml/backup_rules.xml` excluding all app data from cloud backup and device transfer
- [ ] T006 [P] Create `app/src/main/res/values/strings.xml` with every user-visible string, including verbatim: `That PIN did not match. Try again.`; `Dull Mode is limited until VPN setup is complete.`; `Play time pauses during normal phone calls. Calls in other apps, such as WhatsApp, are not slowed down but do not pause play time.`; `Settings could not be saved. Please try again.`; `Kids Garden can be uninstalled or stopped through Android settings.`; `Settings stay on this phone. Kids Garden does not collect content, activity history, or logs.`; notification text `Kids Garden is running`; Parent Mode confirmation stating that locking the phone ends it
- [ ] T007 [P] Create app entry points `app/src/main/java/com/kidsgarden/app/KidsGardenApplication.kt`, `app/src/main/java/com/kidsgarden/app/AppContainer.kt` (manual dependency container, one instance per process) and `app/src/main/java/com/kidsgarden/app/MainActivity.kt` (single Compose activity, no trusted intent extras)
- [ ] T008 [P] Create the privacy review checklist `specs/001-kids-garden-v1/checklists/privacy.md` covering the quickstart literal searches (logging APIs, `isInCall`, `AudioManager`, `QUERY_ALL_PACKAGES`, database/analytics SDKs, hard-coded Compose text) and the constitution review questions

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Domain types, the central reducer and event owner, settings storage, PIN security and the route guard. Every story depends on these.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Domain core

- [ ] T009 [P] Define `AuthorizationMode` (DULL, PARENT_MODE, CHILD_PLAY_SESSION, SOFT_LANDING), `DisplayMode` (adds SETUP_REQUIRED, derived only) and `DullIntensity` (GENTLE, MEDIUM, STRONG) in `domain/src/main/kotlin/com/kidsgarden/app/domain/Mode.kt`
- [ ] T010 [P] Define the `TimeProvider` interface (`elapsedRealtimeMillis()`, `epochMillis()`) in `domain/src/main/kotlin/com/kidsgarden/app/domain/TimeProvider.kt` and a controllable `FakeTimeProvider` in `domain/src/test/kotlin/com/kidsgarden/app/domain/FakeTimeProvider.kt`
- [ ] T011 [P] Define all incoming events and effects from `contracts/state-machine.md` in `domain/src/main/kotlin/com/kidsgarden/app/domain/DomainEvent.kt` (ProcessStarted, UiOpened, ParentUiLeft, PinVerified, PinRejected, StartParentRequested, StartChildRequested(durationMinutes), ManualParentExit, ScreenOff, DeviceLocked, UserPresent, CallAggregateChanged, CallCapabilityChanged, DeadlineObserved, SettingsChanged, SettingsInvalid, SettingsWriteSucceeded/Failed, VpnCapabilityChanged, RebootObserved, PackageReplaced; effects PersistSettings, ApplyNetworkPolicy, ScheduleBoundary, CancelBoundary, ClearParentUiAccess, PublishParentStatus, each carrying an authorization generation). No raw PIN or Android types
- [ ] T012 [P] Define call types in `domain/src/main/kotlin/com/kidsgarden/app/domain/CallState.kt`: `CallObservation(state: ACTIVE|INACTIVE|UNAVAILABLE, monitorGeneration, elapsedMillis)` and `CallDetectionCapability` (AVAILABLE, PERMISSION_MISSING, UNSUPPORTED, UNAVAILABLE); no caller, number, SIM or app identity fields
- [ ] T013 [P] Define ports in `domain/src/main/kotlin/com/kidsgarden/app/domain/Ports.kt`: `BoundaryScheduler` (schedule one elapsed deadline, cancel), `CallStateSource` (observations flow, reconcile), `NetworkPolicySink` (apply policy with generation), `SettingsStore` (observe and write settings snapshots)
- [ ] T014 Define `AuthorizationState` (mode, generation, optional `ChildGrant`, `ParentAccess`) in `domain/src/main/kotlin/com/kidsgarden/app/domain/AuthorizationState.kt` and `ChildGrant` in `domain/src/main/kotlin/com/kidsgarden/app/domain/ChildGrant.kt` with fields D (selected play ms), L (ramp ms), e0, startEpoch, captured profile snapshot, P (completed pause ms), optional pauseStart, and the data-model formulas verbatim: `Consumed play C = (e−e0)−P−pausedNow`, `Remaining play R = max(0, D−C)`, `C < D−L → CHILD_PLAY_SESSION; D−L ≤ C < D → SOFT_LANDING; C ≥ D → DULL`, display projections `expiry = startEpoch+D+P; rampStart = startEpoch+D−L+P`, checked arithmetic, regressed elapsed clock or overflow → invalid
- [ ] T015 Implement `StateMachine.reduce(state, event, time): ReduceResult(state, effects)` skeleton in `domain/src/main/kotlin/com/kidsgarden/app/domain/StateMachine.kt`: exhaustive `when` over events; initial state DULL with no grant or tokens; ProcessStarted/RebootObserved/PackageReplaced → DULL clearing everything; SettingsInvalid and invalid accounting → DULL; PinVerified yields ParentAccess only (no phone grant); unhandled events return state unchanged. Story phases add the remaining transitions
- [ ] T016 Implement `SessionManager` in `domain/src/main/kotlin/com/kidsgarden/app/domain/SessionManager.kt` per `contracts/session-manager.md`: one serialized event queue, `StateFlow<ParentFacingSnapshot>` (displayMode, authorizationMode, vpnStatus, callCapabilityStatus, setupReason, dullIntensity, remainingPlayMillis), effect execution through T013 ports, generation checks so stale acknowledgments are ignored; no transition logic of its own
- [ ] T017 Add foundational reducer tests in `domain/src/test/kotlin/com/kidsgarden/app/domain/StateMachineFoundationTest.kt`: initial DULL, ProcessStarted/reboot/update clear grants and tokens, PIN alone grants nothing, invalid accounting → DULL, unknown events are no-ops
- [ ] T018 [P] Implement `AndroidTimeProvider` (SystemClock.elapsedRealtime, System.currentTimeMillis) in `app/src/main/java/com/kidsgarden/app/platform/time/AndroidTimeProvider.kt`

### Settings storage

- [ ] T019 [P] Implement `SettingsDocument` v1 DTOs in `app/src/main/java/com/kidsgarden/app/data/SettingsDocument.kt` with constraints verbatim: `schemaVersion | Int / 1 | Explicit positive supported version`; `pinCredential | PinCredential? / null | Null means enrollment/recovery, never empty-PIN access`; `dullIntensity | Enum / MEDIUM | GENTLE, MEDIUM, STRONG`; `vpnDullingEnabled | Boolean / false | Parent preference, not live consent or running-service proof`; `vpnDullingConfiguration | ProfileConfiguration | Canonical profile map, no redundant top-level cap values`; `softLandingDurationSeconds | Int / 120 | Exactly 60 or 120`; `setup | SetupFlags | pinCreated and onboardingComplete; permission status is runtime-derived`. Profile initial values `Gentle 256/300; Medium 128/600; Strong 64/1000` with bounds `1–100000 kbps and 0–5000 ms, with monotonic intensity ordering`. No `mode`, `childSession`, pause, token or call field
- [ ] T020 Implement `SettingsDocumentSerializer` in `app/src/main/java/com/kidsgarden/app/data/SettingsDocumentSerializer.kt`: read `schemaVersion` before full decode, `Max serialized size: 64 KiB`, unsupported versions and invalid values → `CorruptionException`, unknown keys → corruption except legacy `mode` and `childSession` which are ignored and dropped on the next write, canonical defaults from data-model.md
- [ ] T021 Implement `DataStoreProvider` in `app/src/main/java/com/kidsgarden/app/data/DataStoreProvider.kt`: exactly one `DataStore<SettingsDocument>` per process at `noBackupFilesDir/kidsgarden-settings.json`, `ReplaceFileCorruptionHandler` returning safe defaults with a transient repair reason, registered in `AppContainer.kt`
- [ ] T022 [P] Implement the migration registry (v1 only, no migrations yet; deterministic, idempotent, never touches DataStore recursively) in `app/src/main/java/com/kidsgarden/app/data/SettingsMigrations.kt`
- [ ] T023 Implement `SettingsRepository` per `contracts/settings-repository.md` in `app/src/main/java/com/kidsgarden/app/data/SettingsRepository.kt`: observeSettings, load, createParentCredential, updateIntensity, updateSoftLandingDuration, updateVpnPreference, updateSetupFlags, changeCredential; typed errors CorruptDocument, UnsupportedSchema, InvalidValue, StaleVersion, IoUnavailable, MigrationFailure; implements the `SettingsStore` port; no authorization or pause operations
- [ ] T024 [P] Add serializer tests in `app/src/test/java/com/kidsgarden/app/data/SettingsDocumentSerializerTest.kt`: defaults, round trip, corrupt JSON, >64 KiB, future schema, out-of-range values, legacy `mode`/`childSession` dropped, any other unknown key rejected, written output never contains authorization fields
- [ ] T025 [P] Add repository tests in `app/src/test/java/com/kidsgarden/app/data/SettingsRepositoryTest.kt` with a temp file: atomic updates, single instance, I/O failure reported and previous value kept, failed `changeCredential` keeps old credential, stale credential version rejected, migration idempotence

### PIN security

- [ ] T026 [P] Implement `PinHasher` in `app/src/main/java/com/kidsgarden/app/security/PinHasher.kt` with constraints verbatim: `credentialVersion | 1`; `algorithm | Exactly PBKDF2WithHmacSHA256`; `iterations | Exactly 600000 for credential version 1`; `derivedKeyLengthBytes | Exactly 32`; `salt | Base64 decoding to exactly 16 bytes from SecureRandom`; `hash | Base64 decoding to exactly 32 verifier bytes`; off-main derivation, constant-time comparison, best-effort char/byte array clearing
- [ ] T027 Implement `ParentPinManager` per `contracts/parent-pin-manager.md` in `app/src/main/java/com/kidsgarden/app/security/ParentPinManager.kt`: six ASCII digits only, enroll, verify, changePin (current PIN + new + confirm, persists via SettingsRepository before confirming), volatile retry gate (5 consecutive failures → 30 s using elapsed time, reset on success, cleared by process death, never persisted)
- [ ] T028 [P] Add PIN tests in `app/src/test/java/com/kidsgarden/app/security/PinHasherTest.kt` and `app/src/test/java/com/kidsgarden/app/security/ParentPinManagerTest.kt`: correct/wrong PIN, non-digit and wrong-length rejection, distinct salts for identical PINs, malformed/unsupported credentials, retry delay and reset, raw PIN absent from persisted bytes

### Navigation and parent gate

- [ ] T029 Implement `KidsGardenNavHost` and `ParentAccessGuard` in `app/src/main/java/com/kidsgarden/app/ui/navigation/KidsGardenNavHost.kt` and `app/src/main/java/com/kidsgarden/app/ui/navigation/ParentAccessGuard.kt`: every dashboard, settings and grant route checks the volatile ParentAccess token on every entry (Back, recents restore, notification tap, deep link); unauthenticated entry routes to setup or PIN; no navigation argument, saved state or intent extra can carry a PIN or assert verification. `MainActivity` emits `ParentUiLeft` through `SessionManager.dispatch` from its `onStop` lifecycle callback and when navigation leaves the protected graph; configuration changes (rotation) do not emit it
- [ ] T030 [P] Add guard tests in `app/src/androidTest/java/com/kidsgarden/app/ui/navigation/ParentAccessGuardTest.kt`: protected routes unreachable without a token via each entry path; token cleared on ParentUiLeft and kept across rotation; once all story routes exist, re-run against every registered protected route

**Checkpoint**: Reducer skeleton, event owner, settings, PIN and guard are tested. Story phases can start.

---

## Phase 3: User Story 1 - Set up a phone that stays dull by default (Priority: P1) 🎯 MVP

**Goal**: Parent installs, creates a PIN, reads limits, grants VPN consent, and the phone is dulled by a working local VPN; unlock never grants normal use.

**Independent Test**: Fresh install → setup → compare network responsiveness against VPN-off; lock/unlock repeatedly without the Kids Garden PIN and confirm DULL remains; decline VPN consent and confirm resumable setup with SETUP_REQUIRED.

### Setup flow

- [ ] T031 [P] [US1] Implement pure `SetupPolicy` (credential present, onboarding complete, VPN consent, forwarding healthy, notification/lifecycle eligibility, storage available → SetupStatus; call capability is never a setup blocker) in `domain/src/main/kotlin/com/kidsgarden/app/domain/SetupPolicy.kt`
- [ ] T032 [P] [US1] Implement `ProtectionStatus` mapping (DULL authorization + missing capability → SETUP_REQUIRED display with calm remedy; saved VPN preference never shown as active protection) in `domain/src/main/kotlin/com/kidsgarden/app/domain/ProtectionStatus.kt`
- [ ] T033 [P] [US1] Implement `PermissionStatusProvider` and `AndroidSetupStatusProvider` in `app/src/main/java/com/kidsgarden/app/permissions/PermissionStatusProvider.kt` and `app/src/main/java/com/kidsgarden/app/permissions/AndroidSetupStatusProvider.kt`, mapping live VPN consent (`VpnService.prepare`), service health, notification permission and storage state into `SetupPolicy` inputs
- [ ] T034 [P] [US1] Implement `WelcomeScreen` and `PrivacyExplanationScreen` (local-only, no content/logs, normal-app limits, no account or factory reset) in `app/src/main/java/com/kidsgarden/app/ui/setup/WelcomeScreen.kt` and `app/src/main/java/com/kidsgarden/app/ui/setup/PrivacyExplanationScreen.kt`
- [ ] T035 [US1] Implement `CreatePinScreen` and `CreatePinViewModel` in `app/src/main/java/com/kidsgarden/app/ui/setup/CreatePinScreen.kt` and `app/src/main/java/com/kidsgarden/app/ui/setup/CreatePinViewModel.kt`: six-digit numeric masked entry plus confirmation, PIN passed transiently to `ParentPinManager.enroll`, no `rememberSaveable`/SavedStateHandle for PIN text, input cleared on recreation
- [ ] T036 [US1] Implement `VpnSetupScreen` and `VpnPermissionLauncher` in `app/src/main/java/com/kidsgarden/app/ui/setup/VpnSetupScreen.kt` and `app/src/main/java/com/kidsgarden/app/permissions/VpnPermissionLauncher.kt`: explanation shown before `VpnService.prepare` consent, calm denial state, live capability (never the saved flag) displayed
- [ ] T037 [P] [US1] Implement `NotificationPermissionScreen` and `NotificationPermissionLauncher` in `app/src/main/java/com/kidsgarden/app/ui/setup/NotificationPermissionScreen.kt` and `app/src/main/java/com/kidsgarden/app/permissions/NotificationPermissionLauncher.kt`: parent explanation before the POST_NOTIFICATIONS prompt; denial respected and reflected in setup status
- [ ] T038 [US1] Wire the setup sequence (welcome → privacy → create PIN → notification → VPN) into `app/src/main/java/com/kidsgarden/app/ui/navigation/KidsGardenNavHost.kt`, persisting progress through `SettingsRepository.updateSetupFlags`; interrupted setup resumes at the first incomplete step and never creates a grant

### Working DULL via local VPN

- [ ] T039 [P] [US1] Implement `ThrottleProfile` and `VpnPolicyMapper` in `app/src/main/java/com/kidsgarden/app/vpn/ThrottleProfile.kt` and `app/src/main/java/com/kidsgarden/app/vpn/VpnPolicyMapper.kt`: policies Normal, Dull(intensity), SoftLanding(target, fraction), Unavailable(reason); `NORMAL is a domain policy with no cap and zero artificial delay`; rates are aggregate per direction with `rateBytesPerSecond = kbps×1000/8`; added RTT split half per direction
- [ ] T040 [US1] Audit forwarding-engine candidates (source, license, Android 15/16, TCP/UDP, IPv4/IPv6, QUIC, 4 KB/16 KB pages, logging paths, mapped DNS, maintenance) and record the pinned choice and rejected options in `specs/001-kids-garden-v1/evidence/forwarding-engine-selection.md`; reject anything needing a remote gateway, content/DNS-name inspection, destination history or unavoidable logging
- [ ] T041 [US1] Implement `ForwardingEngine` in `app/src/main/java/com/kidsgarden/app/vpn/ForwardingEngine.kt` (plus the audited adapter under `app/src/main/cpp/` if selected in T040): `start(configuration, protectSocket)` returns Running only once TUN traffic actually forwards; TCP/UDP over IPv4/IPv6; every egress socket passed through `VpnService.protect`; underlying-network handover; authenticated loopback-only relay only if required; `apply(policy, generation)`; idempotent `stop()` clearing buffers and flow maps; errors are allowlisted reason codes only; no payload, DNS-name, SNI or URL parsing, packet dumps or output
- [ ] T042 [US1] Implement `TrafficShapingScheduler` in `app/src/main/java/com/kidsgarden/app/vpn/TrafficShapingScheduler.kt`: bounded fair per-direction queues (initial budgets 16 MiB queued bytes, 1024 flows), monotonic release times, aggregate rate budgets, added-latency scheduling, TCP backpressure, bounded datagram overflow, stale-generation rejection, Normal drains artificial delay without resetting flows
- [ ] T043 [US1] Implement `VpnDullingController` per `contracts/vpn-dulling-controller.md` in `app/src/main/java/com/kidsgarden/app/vpn/VpnDullingController.kt` (implements `NetworkPolicySink`): capability StateFlow from live consent/service/engine health, start/apply/stop with generations, RUNNING only after real forwarding, stop fails open (ordinary routing, never blackhole)
- [ ] T044 [US1] Implement `KidsGardenVpnService` in `app/src/main/java/com/kidsgarden/app/vpn/KidsGardenVpnService.kt`: start foreground with type systemExempted and the neutral `Kids Garden is running` notification (no mode/session details, lockscreen-safe, tap routes to PIN), `Builder.establish()` null handling, `onRevoke` → capability lost, no restart loops, opt out of always-on
- [ ] T045 [US1] Add DULL and capability transitions to `domain/src/main/kotlin/com/kidsgarden/app/domain/StateMachine.kt`: DULL emits ApplyNetworkPolicy(Dull(selected intensity)) when capability is healthy; VpnCapabilityChanged(lost) cancels any grant to DULL and sets SETUP_REQUIRED display; capability restored never creates a grant; UserPresent/UiOpened never grant
- [ ] T046 [US1] Wire `VpnDullingController`, `SettingsRepository`, `AndroidTimeProvider` and setup providers into `SessionManager` inside `app/src/main/java/com/kidsgarden/app/AppContainer.kt`, starting the VPN only from the visible parent setup flow
- [ ] T047 [US1] Add default-DULL tests in `domain/src/test/kotlin/com/kidsgarden/app/domain/DefaultDullTest.kt`: unlock/UserPresent/UiOpened/PIN-without-action stay DULL; capability loss → DULL + SETUP_REQUIRED; capability restore → DULL with no grant; SettingsInvalid → DULL with safe defaults
- [ ] T048 [P] [US1] Add policy tests in `app/src/test/java/com/kidsgarden/app/vpn/VpnPolicyMapperTest.kt` and `app/src/test/java/com/kidsgarden/app/vpn/FailSafePolicyTest.kt` with a fake engine: profile numbers, Normal has no cap, Unavailable is distinguishable from Normal, stale generations ignored, engine failure revokes and fails open
- [ ] T049 [P] [US1] Add setup UI tests in `app/src/androidTest/java/com/kidsgarden/app/ui/setup/SetupFlowTest.kt`: full flow with fake platform adapters, VPN denial → resumable SETUP_REQUIRED, explanation screens precede each prompt, no grant after consent
- [ ] T050 [US1] Run the isolated forwarding qualification (TCP, UDP/QUIC, IPv4/IPv6, DNS transport, MTU, handover, concurrent flows, Normal overhead ≥90% throughput and ≤50 ms added RTT, each Dull profile) and record synthetic results in `specs/001-kids-garden-v1/evidence/vpn-feasibility.md` (closes the G-VPN prototype gate)

**Checkpoint**: The phone is dull by default with honest capability status. This is the MVP.

---

## Phase 4: User Story 2 - Use the phone as a parent until it locks (Priority: P1)

**Goal**: PIN + "Use phone as parent" grants untimed Normal use that ends on screen off, lock, manual exit, invalid state or restart.

**Independent Test**: Start Parent Mode, switch apps (stays), then separately screen-off, manual lock, auto lock and unlock (each returns DULL; unlock never restores).

- [ ] T051 [US2] Add Parent Mode transitions to `domain/src/main/kotlin/com/kidsgarden/app/domain/StateMachine.kt`: StartParentRequested requires a fresh one-use grant permission, interactive/unlocked state and healthy capability, commits in memory, bumps generation, emits ApplyNetworkPolicy(Normal); ScreenOff, DeviceLocked, UserPresent (defensive), ManualParentExit (no PIN), invalid state and ProcessStarted revoke to DULL; a concurrent lock wins over a pending start; ParentUiLeft clears ParentAccess only; calls never revoke Parent Mode
- [ ] T052 [US2] Add Parent Mode tests in `domain/src/test/kotlin/com/kidsgarden/app/domain/ParentModeTest.kt`: entry requires fresh permission, reused permission rejected, app switching keeps the grant, each revocation path, unlock never restores, lock-vs-start race, call start/end keeps a valid grant, stale generation acknowledgment ignored
- [ ] T053 [US2] Implement `ParentPinScreen` and `ParentPinViewModel` in `app/src/main/java/com/kidsgarden/app/ui/pin/ParentPinScreen.kt` and `app/src/main/java/com/kidsgarden/app/ui/pin/ParentPinViewModel.kt`: masked six-digit entry, calm wrong-PIN string, retry-delay state, success yields ParentAccess and routes to actions without changing DULL. Register the `pin` route in `app/src/main/java/com/kidsgarden/app/ui/navigation/KidsGardenNavHost.kt` as the default enrolled entry route
- [ ] T054 [US2] Implement `ParentActionScreen` with “Use phone as parent”, “Give phone to child” and “Settings” in `app/src/main/java/com/kidsgarden/app/ui/dashboard/ParentActionScreen.kt`; register the `actions` route in `app/src/main/java/com/kidsgarden/app/ui/navigation/KidsGardenNavHost.kt` behind `ParentAccessGuard`
- [ ] T055 [US2] Implement `ParentModeScreen` (no timer, confirmation that locking ends Parent Mode, manual exit) in `app/src/main/java/com/kidsgarden/app/ui/dashboard/ParentModeScreen.kt`; register the `parentMode` route in `app/src/main/java/com/kidsgarden/app/ui/navigation/KidsGardenNavHost.kt` behind `ParentAccessGuard`
- [ ] T056 [US2] Implement runtime-registered `ScreenStateReceiver` and `UserPresentReceiver` plus `DeviceEventCoordinator` in `app/src/main/java/com/kidsgarden/app/receivers/ScreenStateReceiver.kt`, `app/src/main/java/com/kidsgarden/app/receivers/UserPresentReceiver.kt` and `app/src/main/java/com/kidsgarden/app/receivers/DeviceEventCoordinator.kt`: translate SCREEN_OFF, USER_PRESENT and sampled `isInteractive`/`isDeviceLocked` (on start, resume and before relaxing policy) into domain events; no policy decisions in receivers
- [ ] T057 [P] [US2] Add Parent Mode UI tests in `app/src/androidTest/java/com/kidsgarden/app/ui/ParentModeFlowTest.kt`: correct/wrong PIN, retry delay, action selection, confirmation copy, manual exit

**Checkpoint**: US1 and US2 work independently.

---

## Phase 5: User Story 3 - Give the child a fixed play session (Priority: P1)

**Goal**: PIN + "Give phone to child" + 10/20/30 minutes; time runs through lock, sleep and offline; only normal phone calls pause it; parent controls lock immediately.

**Independent Test**: With fake time, 30-minute grant → 5 minutes play → 25-minute phone call → 25 minutes remain; lock/unlock never pauses; expiry while locked → DULL; WhatsApp call → no pause; after start, Back/recents/notification/deep link all require the PIN.

- [ ] T058 [US3] Add child-grant transitions to `domain/src/main/kotlin/com/kidsgarden/app/domain/StateMachine.kt`: StartChildRequested accepts exactly 10, 20 or 30 minutes with a fresh permission, captures intensity and ramp length L, creates a `ChildGrant` in memory, emits ClearParentUiAccess and ApplyNetworkPolicy(Normal) and ScheduleBoundary(next boundary); a new grant replaces the old one without stacking; DeadlineObserved evaluates C from absolute elapsed anchors; C ≥ D → DULL and discard grant; duplicate or stale start commands rejected
- [ ] T059 [US3] Add telephony pause rules to `domain/src/main/kotlin/com/kidsgarden/app/domain/StateMachine.kt`: CallAggregateChanged(ACTIVE) on a grant with R > 0 sets pauseStart; repeated ACTIVE adds nothing; INACTIVE/UNAVAILABLE closes once (P += e−pauseStart); zero remaining at call start → DULL, no credit; a grant started while ACTIVE starts paused with full D; the pre-pause boundary stays scheduled as a mandatory fresh-state checkpoint where cached ACTIVE cannot renew and a fresh INACTIVE/UNAVAILABLE/failure closes the pause; stale monitor generations ignored; lock/unlock never alter a pause
- [ ] T060 [US3] Implement `ServiceBoundaryScheduler` (one-shot delay to the next elapsed-realtime boundary inside the VPN service; implements `BoundaryScheduler`) in `app/src/main/java/com/kidsgarden/app/vpn/ServiceBoundaryScheduler.kt`, and make `ForwardingEngine.kt` compare `elapsedRealtime()` to the policy deadline before forwarding under Normal; no exact alarms, WorkManager or wake locks
- [ ] T061 [US3] Implement `TelephonyCallStateMonitor` per `contracts/call-state-monitor.md` in `app/src/main/java/com/kidsgarden/app/calls/TelephonyCallStateMonitor.kt` (implements `CallStateSource`): one `TelephonyCallback.CallStateListener` per active subscription via `createForSubscriptionId`, re-registration on subscription changes, OFFHOOK → ACTIVE, RINGING/IDLE → INACTIVE, aggregate across subscriptions, permission missing/lost → UNAVAILABLE, fresh-state `reconcile()`, monitor generation; never uses `TelecomManager.isInCall`, device-wide `getCallState` or `AudioManager`; keep the pure mapping/aggregation in `app/src/main/java/com/kidsgarden/app/calls/TelephonyStateAggregator.kt`
- [ ] T062 [US3] Implement `PhoneStatePermissionScreen` and `PhoneStatePermissionLauncher` in `app/src/main/java/com/kidsgarden/app/ui/setup/PhoneStatePermissionScreen.kt` and `app/src/main/java/com/kidsgarden/app/permissions/PhoneStatePermissionLauncher.kt`: parent explanation before the READ_PHONE_STATE prompt; denial shows the call limitation string and never blocks setup, sessions or dulling. Insert the `phoneStatePermission` step into the setup sequence in `app/src/main/java/com/kidsgarden/app/ui/navigation/KidsGardenNavHost.kt` after the VPN step, with a Skip option; persist nothing about the choice (status is read live)
- [ ] T063 [US3] Implement `ChildDurationScreen` and `ChildSessionViewModel` in `app/src/main/java/com/kidsgarden/app/ui/session/ChildDurationScreen.kt` and `app/src/main/java/com/kidsgarden/app/ui/session/ChildSessionViewModel.kt`: 10/20/30 choices only, abandoning selection starts nothing, start clears the protected back stack and shows a brief noninteractive confirmation, then the neutral PIN entry. Register the `childDuration` route in `app/src/main/java/com/kidsgarden/app/ui/navigation/KidsGardenNavHost.kt` behind `ParentAccessGuard`; on start pop the whole protected back stack to the `pin` route
- [ ] T064 [US3] Add child-session tests in `domain/src/test/kotlin/com/kidsgarden/app/domain/ChildSessionTest.kt`: each duration, locked minutes count, lock/unlock invariance, expiry while locked → DULL on next evaluation, replacement without stacking, wall-clock/timezone changes add nothing, regressed elapsed clock and overflow → DULL, exact boundary instants
- [ ] T065 [US3] Add call-pause tests in `domain/src/test/kotlin/com/kidsgarden/app/domain/CallPauseTest.kt`: 30/5/25 example leaves exactly 25 minutes, dual-SIM/overlapping ACTIVE forms one pause, duplicate end callbacks add nothing, ringing-only no pause, zero-remaining call, grant started during a call, parent lock during a call still revokes Parent Mode, call end never revives a revoked or replaced grant, permission loss resumes countdown
- [ ] T066 [US3] Add the lost-end-callback test in `domain/src/test/kotlin/com/kidsgarden/app/domain/CallObservationRecoveryTest.kt`: suppressed end callback, mandatory checkpoint rejects cached ACTIVE, fresh INACTIVE/UNAVAILABLE resumes the same remainder, fresh ACTIVE only sets the next bounded checkpoint, no path freezes time indefinitely
- [ ] T067 [P] [US3] Add aggregator tests in `app/src/test/java/com/kidsgarden/app/calls/TelephonyStateAggregatorTest.kt`: per-subscription mapping, aggregation, subscription removal, permission loss → UNAVAILABLE, stale generation, and confirmation that the API accepts no third-party or audio-mode input
- [ ] T068 [P] [US3] Add child handoff UI tests in `app/src/androidTest/java/com/kidsgarden/app/ui/ChildSessionFlowTest.kt`: duration selection, inert confirmation, Back/recents/notification/deep link after start all reach the PIN screen, no child-facing countdown or warning

**Checkpoint**: US1–US3 work independently.

---

## Phase 6: User Story 4 - End play through a quiet Soft Landing (Priority: P1)

**Goal**: The last 1 or 2 minutes of play slow the network gradually toward the session's captured profile, freeze during phone calls, and end in DULL with no app-created child surface.

**Independent Test**: Fake-time ramp passes ≥3 increasing levels, never exceeds the target (Gentle never reaches Medium), resumes from saved progress after a call with 45 s left, and ends in DULL with no UI, sound or notification change.

- [ ] T069 [US4] Implement `SoftLandingPolicy` in `domain/src/main/kotlin/com/kidsgarden/app/domain/SoftLandingPolicy.kt`: ramp length from the captured `softLandingDurationSeconds` (`Exactly 60 or 120`), stages at p = 1/3, 2/3 and 1 starting from Normal, conceptual cap K/p and added delay p×L, monotonic and never more severe than the captured target
- [ ] T070 [US4] Integrate Soft Landing into `domain/src/main/kotlin/com/kidsgarden/app/domain/StateMachine.kt` and `domain/src/main/kotlin/com/kidsgarden/app/domain/DullModePolicy.kt`: enter SOFT_LANDING at C = D−L, emit staged ApplyNetworkPolicy and ScheduleBoundary per stage, freeze stage during a call pause and resume from saved progress, DULL at effective expiry, settings changes during a session apply only afterward, no child-facing effect of any kind
- [ ] T071 [US4] Add Soft Landing tests in `domain/src/test/kotlin/com/kidsgarden/app/domain/SoftLandingPolicyTest.kt`: three increasing stages for each intensity, explicit Gentle non-overshoot (never reaches Medium or Strong), exact boundary instants, 1- and 2-minute ramps, delayed wake skipping stages lands on the correct one, 45-second paused ramp resumes without replay, intensity change mid-session ignored until next grant
- [ ] T072 [P] [US4] Add quiet-expiry UI test in `app/src/androidTest/java/com/kidsgarden/app/ui/SoftLandingTest.kt`: at expiry no activity is launched, no dialog, sound or vibration occurs, and the foreground notification text is unchanged

**Checkpoint**: All P1 session behavior works.

---

## Phase 7: User Story 5 - Manage settings and see actual protection (Priority: P2)

**Goal**: Behind the PIN, the parent sees true mode/VPN/setup status and changes PIN, intensity, Soft Landing length and VPN state; limits and privacy are explained honestly.

**Independent Test**: Change each setting, restart the app and confirm persistence; confirm unauthenticated entry cannot reach settings; revoke VPN consent and see SETUP_REQUIRED; old PIN fails after a change.

- [ ] T073 [US5] Implement `DashboardViewModel` and `DashboardScreen` in `app/src/main/java/com/kidsgarden/app/ui/dashboard/DashboardViewModel.kt` and `app/src/main/java/com/kidsgarden/app/ui/dashboard/DashboardScreen.kt`: display mode, actual VPN status, intensity, setup status, nonnegative remaining play (frozen during a pause), call-detection limitation when unavailable; display ticker never mutates state. Register the `dashboard` route in `app/src/main/java/com/kidsgarden/app/ui/navigation/KidsGardenNavHost.kt` behind `ParentAccessGuard`
- [ ] T074 [US5] Implement `SettingsScreen` in `app/src/main/java/com/kidsgarden/app/ui/settings/SettingsScreen.kt`: intensity (Gentle/Medium/Strong), Soft Landing 1 or 2 minutes, VPN status with stop and re-enable, links to change PIN, limitations and privacy; note that Android's own VPN settings are outside PIN control; while call detection is PERMISSION_MISSING show an “Enable call pause” entry opening `PhoneStatePermissionScreen`. Register the `settings` route in `app/src/main/java/com/kidsgarden/app/ui/navigation/KidsGardenNavHost.kt` behind `ParentAccessGuard`
- [ ] T075 [US5] Implement `ChangePinScreen` and `ChangePinViewModel` in `app/src/main/java/com/kidsgarden/app/ui/settings/ChangePinScreen.kt` and `app/src/main/java/com/kidsgarden/app/ui/settings/ChangePinViewModel.kt`: current PIN, new PIN and confirmation; success only after durable save; failure keeps the old PIN with the storage string. Register the `changePin` route in `app/src/main/java/com/kidsgarden/app/ui/navigation/KidsGardenNavHost.kt` behind `ParentAccessGuard`
- [ ] T076 [US5] Implement `LimitationsScreen` and `PrivacyScreen` in `app/src/main/java/com/kidsgarden/app/ui/settings/LimitationsScreen.kt` and `app/src/main/java/com/kidsgarden/app/ui/settings/PrivacyScreen.kt`: normal installation, uninstall/force-stop exposure, no full OS control or app blocking, best-effort dulling, cached/offline limits, the exact call limitation string, other VPN conflict, forgotten-PIN help (clear data/reinstall), future Device Owner protection, no content/logs. Register the `limitations` and `privacy` routes in `app/src/main/java/com/kidsgarden/app/ui/navigation/KidsGardenNavHost.kt` behind `ParentAccessGuard`
- [ ] T077 [US5] Add settings transitions to `domain/src/main/kotlin/com/kidsgarden/app/domain/StateMachine.kt`: SettingsChanged(intensity) re-applies the Dull policy immediately in DULL and leaves a running session's captured target; successful PIN change revokes every grant and ParentAccess; VPN preference off revokes grants before stop; SettingsWriteFailed leaves authorization unchanged
- [ ] T078 [US5] Add settings-effect tests in `domain/src/test/kotlin/com/kidsgarden/app/domain/SettingsEffectsTest.kt` covering each T077 rule
- [ ] T079 [P] [US5] Add dashboard and settings UI tests in `app/src/androidTest/java/com/kidsgarden/app/ui/DashboardSettingsTest.kt`: each status, each setting change, PIN-gated entry, call limitation shown only to the parent, exact copy from strings.xml

**Checkpoint**: All stories except recovery hardening are complete.

---

## Phase 8: User Story 6 - Recover safely without surveillance records (Priority: P1)

**Goal**: Failures, restarts and invalid data end in DULL; phone, messaging and WhatsApp traffic is never slowed; no records of the child's activity exist.

**Independent Test**: Inject corrupt settings, clock changes, process kill and reboot (always DULL, settings kept); inspect storage (allowlisted keys only); in DULL, an RCS photo and a WhatsApp call are unslowed while other traffic is dull.

- [ ] T080 [US6] Implement `CommunicationAppExclusions` in `app/src/main/java/com/kidsgarden/app/vpn/CommunicationAppExclusions.kt`: before each `establish()`, resolve `TelecomManager.getDefaultDialerPackage()`, `getSystemDialerPackage()`, `Telephony.Sms.getDefaultSmsPackage(context)` and `com.whatsapp`, call `addDisallowedApplication` per package, skip a package on `NameNotFoundException` or null default, return a per-package applied/skipped result, persist nothing; behind a small package-resolver interface for testing
- [ ] T081 [US6] Call `CommunicationAppExclusions` from `KidsGardenVpnService.kt` before every `establish()` and expose the coarse exclusion result through `VpnDullingController.kt` capability; never mix allowed and disallowed app lists
- [ ] T082 [US6] Implement `BootReceiver` in `app/src/main/java/com/kidsgarden/app/receivers/BootReceiver.kt` (BOOT_COMPLETED, MY_PACKAGE_REPLACED → RebootObserved/PackageReplaced, bounded work, recovery only through permitted entry points) and extend `DeviceEventCoordinator.kt` with VPN revoke, competing VPN and user-stop events
- [ ] T083 [US6] Add recovery tests in `domain/src/test/kotlin/com/kidsgarden/app/domain/RecoveryTest.kt`: every restart path starts at DULL with no grant, SettingsInvalid → safe defaults and re-enrollment when the credential is lost, clock changes in both directions add nothing, VPN loss during a child session → DULL, no restored authorization from any source
- [ ] T084 [P] [US6] Add exclusion tests in `app/src/test/java/com/kidsgarden/app/vpn/CommunicationAppExclusionsTest.kt`: all present, WhatsApp missing, null default SMS, exclusion failure for one package, no whole-device bypass, changed default SMS applied on next establish
- [ ] T085 [P] [US6] Add storage inspection test in `app/src/androidTest/java/com/kidsgarden/app/data/StoragePrivacyTest.kt`: after setup, grants, calls, wrong PINs and recovery, the settings file contains only allowlisted keys and no raw PIN, mode, session, call or destination data, and the app directory contains no other product files
- [ ] T086 [US6] Complete the manifest/source/dependency/native privacy audit (no logs, analytics, history, URLs, domains, call identity, raw PIN; backup exclusions verified on device) using `specs/001-kids-garden-v1/checklists/privacy.md` and record it in `specs/001-kids-garden-v1/evidence/privacy-audit.md` (G-PRIVACY)

**Checkpoint**: All user stories are complete and independently tested.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Accessibility, device qualification, performance and release.

- [ ] T087 [P] Add accessibility tests in `app/src/androidTest/java/com/kidsgarden/app/ui/AccessibilityTest.kt` (TalkBack labels, focus order, 200% font scale on PIN and action screens, lockscreen notification shows no PIN or session details) and fix findings in the affected screens
- [ ] T088 [P] Record API 35/36 lifecycle qualification (screen-off, keyguard paths, process death, reboot, update, VPN revoke, competing VPN, foreground-service eligibility) in `specs/001-kids-garden-v1/evidence/lifecycle-qa.md` (G-LOCK, G-LIFECYCLE), including at least 20 lock/screen-off cycles per OS version in Parent Mode with zero surviving grants (SC-003)
- [ ] T089 [P] Record call qualification in `specs/001-kids-garden-v1/evidence/call-qualification.md`: cellular and Wi-Fi calls pause on single and dual SIM; WhatsApp call and a game voice chat do not pause; permission denial/revocation; dialer, default SMS/RCS (RCS photo in DULL) and WhatsApp exclusions; default SMS change at next establish; for each of 10/20/30 minutes on each OS version, at least 10 lock/unlock cycles both with and without a phone call, confirming deadlines move only by call time and the 30/5/25 example leaves exactly 25 minutes (SC-004). Never place live emergency calls
- [ ] T090 Record performance and battery results (NFR-002 one-second responsiveness, NFR-003 Normal overhead, SC-006 profile differentiation, NFR-004 two-hour idle ≤2 percentage points) in `specs/001-kids-garden-v1/evidence/vpn-qa.md`
- [ ] T091 [P] Run the SC-001 parent setup study and SC-006 adult blind comparison with adult participants and synthetic sessions; record results in `specs/001-kids-garden-v1/evidence/usability.md`
- [ ] T092 Run all suites and the quickstart scenario table (`.\gradlew.bat :domain:test :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest`) and record results in `specs/001-kids-garden-v1/evidence/qa-results.md` (G-QUALITY)
- [ ] T093 Update `specs/001-kids-garden-v1/plan.md` gate register and `specs/001-kids-garden-v1/research.md` with recorded evidence; report unmet items honestly
- [ ] T094 Add private APK signing and sideload instructions to `specs/001-kids-garden-v1/quickstart.md` only after all gates pass; keep keys outside the repository

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: depends on Setup; blocks all stories.
- **US1 (Phase 3)**: depends on Foundational. MVP.
- **US2 (Phase 4)**: depends on Foundational; needs US1's VPN controller (T043–T046) to apply Normal for real, but its reducer and UI work can start right after Phase 2.
- **US3 (Phase 5)**: depends on Foundational and US1's VPN service (T044) for `ServiceBoundaryScheduler`; reuses US2's PIN/action screens (T053–T054).
- **US4 (Phase 6)**: depends on US3's grant and boundary logic (T058–T060).
- **US5 (Phase 7)**: depends on Foundational; dashboard remaining-time display needs US3 (T058).
- **US6 (Phase 8)**: exclusions depend on US1's VPN service (T044); recovery tests cover all prior transitions.
- **Polish (Phase 9)**: depends on all stories.

### Key task dependencies

- T009–T014 → T015 → T016 → T017; every StateMachine task (T045, T051, T058, T059, T070, T077) runs after T015 in ID order.
- T019 → T020 → T021 → T023; T026 → T027 (needs T023).
- T040 → T041 → T042 → T043 → T044 → T046 → T050.
- T044 → T060, T080 → T081.
- T058 → T059 → T064–T066; T061 → T067.
- T069 → T070 → T071.

### Within each story

- Domain transitions before their tests' final run; tests may be written first and fail.
- Platform adapters before wiring; wiring before UI tests that rely on it.

---

## Parallel Examples

### Phase 2

```text
T009 Mode.kt | T010 TimeProvider.kt | T011 DomainEvent.kt | T012 CallState.kt | T013 Ports.kt
T019 SettingsDocument.kt | T022 SettingsMigrations.kt | T026 PinHasher.kt | T018 AndroidTimeProvider.kt
```

### User Story 1

```text
T031 SetupPolicy.kt | T032 ProtectionStatus.kt | T033 AndroidSetupStatusProvider.kt | T034 WelcomeScreen.kt | T037 NotificationPermissionScreen.kt | T039 ThrottleProfile.kt
```

### User Story 2

```text
T057 ParentModeFlowTest.kt alongside T051/T052 domain work (T053–T055 share KidsGardenNavHost.kt and run in order)
```

### User Story 3

```text
T067 TelephonyStateAggregatorTest.kt | T068 ChildSessionFlowTest.kt
```

### User Story 5

```text
T079 DashboardSettingsTest.kt alongside T077/T078 domain work (T073–T076 share KidsGardenNavHost.kt and run in order)
```

### User Story 6

```text
T084 CommunicationAppExclusionsTest.kt | T085 StoragePrivacyTest.kt
```

---

## Implementation Strategy

### MVP first (User Story 1)

1. Phase 1 Setup → Phase 2 Foundational.
2. Phase 3 (US1), including the real forwarding engine and T050 qualification.
3. Stop and validate: the phone is dull by default, unlock grants nothing, status is honest. This is the riskiest technical piece, so it goes first.

### Incremental delivery

1. US2 Parent Mode → validate lock revocation on devices.
2. US3 child sessions with telephony pause → validate 30/5/25 and handoff.
3. US4 Soft Landing → validate quiet expiry.
4. US5 settings and dashboard.
5. US6 exclusions and recovery hardening.
6. Phase 9 qualification, then private APK.

### Team split (Claude Code + Codex)

After Phase 2, one agent owns `StateMachine.kt`/`SessionManager.kt` tasks serially while the other takes [P] UI and adapter tasks in the same story. Never edit `StateMachine.kt`, `SessionManager.kt`, `AppContainer.kt` or the manifest from two agents at once.

---

## Notes

- [P] tasks touch different files and have no incomplete dependencies.
- Commit after each task or logical group; run `:domain:test` after every StateMachine change.
- Out of scope for V1 (V2 backlog): third-party call pause, parent-managed communication allowlist, Device Owner/DPC, schedules, remote control, other languages.

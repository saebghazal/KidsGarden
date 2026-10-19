# Data Model: Kids Garden V1

**Status**: Scoped V1 design, updated for memory-only authorization, telephony-only call pause, the parent Active/Deactivated switch (FR-031, 2026-10-15), and screen and sound dulling with method switches (FR-034–036, 2026-10-18). Implementation can start; no production data file or device qualification is created by this document.

## Ownership and boundaries

One application process owns one DataStore instance, one SettingsRepository, one SessionManager and one VPN controller. Only SettingsRepository writes persisted state, and it persists settings only. DTOs are immutable and serializable; domain types have no Android or serialization dependency.

Two independent concepts:

- **Authorization**: DULL, PARENT_MODE, CHILD_PLAY_SESSION, SOFT_LANDING, DEACTIVATED. Held only in `SessionManager` memory; every process start begins at DULL with no session, or at DEACTIVATED when the valid saved `active` setting is false.
- **Protection capability / display mode**: actual VPN readiness, storage health and setup status. Call detection capability is separate and cannot block setup/grants/dulling. SETUP_REQUIRED is presentation only.

Only a detectable active mobile/Telecom call pauses child/ramp accounting. Fixed communication-app VPN exclusions are independent of detection and never pause play time. DULL remains active on non-excluded traffic; missing call detection continues countdown without a global bypass.

## Persisted SettingsDocument v2

Stored as UTF-8 JSON at an app-private no-backup location, initially `kidsgarden-settings.json`. Max serialized size: 64 KiB. No directory of historical versions, settings exports, backup files, logs, or traffic files. DataStore's internal atomic-write mechanics do not authorize product history.

| Field | Type / default | Validation and meaning |
| --- | --- | --- |
| schemaVersion | Int / 2 | Explicit positive supported version (1 or 2); inspect before normal decoding; v1 is migrated |
| active | Boolean / true | Parent's Active/Deactivated choice (FR-031). false only after an authenticated, confirmed Deactivate; never set by errors, migration or recovery |
| pinCredential | PinCredential? / null | Null means enrollment/recovery, never empty-PIN access |
| dullIntensity | Enum / MEDIUM | GENTLE, MEDIUM, STRONG |
| vpnDullingEnabled | Boolean / false | The Network dulling switch (“Network dulling” = VPN dulling) (set true when setup completes with a passing check); parent preference, not live consent or running-service proof |
| screenEffects | ScreenEffects | Grey screen, lower media volume, lower brightness switches and cycle timings (FR-034–036) |
| brightnessRestore | BrightnessRestore? / null | The parent's brightness level and auto setting saved before the brightness effect first lowers it; cleared after restore |
| vpnDullingConfiguration | ProfileConfiguration | Canonical profile map, no redundant top-level cap values |
| softLandingDurationSeconds | Int / 120 | Exactly 60 or 120 |
| setup | SetupFlags | pinCreated and onboardingComplete; permission status is runtime-derived |

`active` is a parent setting, not authorization: it never creates a grant, and `true` still needs PIN plus action for normal use. There is no `mode`, `childSession`, pause, token, or call field. Decoding treats any other unknown key as corruption, except the legacy keys `mode` and `childSession`, which are ignored and dropped on the next write (FR-026).

### PinCredential

| Field | Value / validation |
| --- | --- |
| credentialVersion | 1 |
| algorithm | Exactly PBKDF2WithHmacSHA256 |
| iterations | Exactly 600000 for credential version 1 |
| derivedKeyLengthBytes | Exactly 32 |
| salt | Base64 decoding to exactly 16 bytes from SecureRandom |
| hash | Base64 decoding to exactly 32 verifier bytes |

No raw PIN, reversible PIN encryption, hint, recovery answer, failed-attempt count or last-attempt time. Validate field lengths and allowlisted parameters before decoding/deriving. Reject malformed encodings and unsupported algorithms/work factors. Future parameter upgrades require a reviewed credential version and migration policy; do not accept attacker-controlled work factors from JSON.

### ProfileConfiguration

Map `GENTLE`, `MEDIUM`, `STRONG` to aggregate per-direction `bandwidthKbps` and `addedRttLatencyMs`.

Initial values: Gentle 256/300; Medium 128/600; Strong 64/1000. Values are positive rate and nonnegative finite integer latency, with Strong's initial delay within the requested 800–1200 ms range. Defensive v1 serializer bounds: 1–100000 kbps and 0–5000 ms, with monotonic intensity ordering. Only parent intensity selection is exposed; numeric tuning is a reviewed configuration change, not a child-editable field.

NORMAL is a domain policy with no cap and zero artificial delay, not bandwidthKbps=0 interpreted as blackholing. Persist no per-app, domain, IP-address or category rule.

### ScreenEffects

| Field | Type / default | Validation |
| --- | --- | --- |
| greyScreenEnabled | Boolean / false | Effect switch |
| lowerVolumeEnabled | Boolean / false | Effect switch |
| lowerBrightnessEnabled | Boolean / false | Effect switch |
| buildUpSeconds | Int / 120 | 60, 120, 180, 240 or 300 |
| breakSeconds | Int / 60 | 0, 60, 120, 180, 240 or 300; 0 holds at maximum |

At least one of `vpnDullingEnabled` and the three effect switches must be true once setup is complete; a document violating this is invalid for that field set and the write is rejected. Maximum strengths are fixed per intensity in code (tint 0.40/0.60/0.75 alpha; media cap 50/30/15% of max; brightness 60/40/20% of the saved level), not stored.

### BrightnessRestore

| Field | Type | Validation |
| --- | --- | --- |
| level | Int | 0–255 as read from `Settings.System.SCREEN_BRIGHTNESS` |
| automatic | Boolean | Previous `SCREEN_BRIGHTNESS_MODE` was automatic |

Written once per DULL period before the first brightness change and cleared after restore; restored first at process start if present. It is the parent's own device setting, not child activity.

### SetupFlags

`pinCreated` defaults false and must agree with a valid credential. `onboardingComplete` defaults false. Runtime VPN, notification and phone-state permissions are checked with the platform; there is no persisted `vpnPermissionGranted` field that could be mistaken for authority. Revocation may leave onboardingComplete true while the parent sees SETUP_REQUIRED.

### Canonical initial JSON

```json
{
  "schemaVersion": 2,
  "active": true,
  "pinCredential": null,
  "dullIntensity": "MEDIUM",
  "vpnDullingEnabled": false,
  "screenEffects": {
    "greyScreenEnabled": false,
    "lowerVolumeEnabled": false,
    "lowerBrightnessEnabled": false,
    "buildUpSeconds": 120,
    "breakSeconds": 60
  },
  "brightnessRestore": null,
  "vpnDullingConfiguration": {
    "GENTLE": { "bandwidthKbps": 256, "addedRttLatencyMs": 300 },
    "MEDIUM": { "bandwidthKbps": 128, "addedRttLatencyMs": 600 },
    "STRONG": { "bandwidthKbps": 64, "addedRttLatencyMs": 1000 }
  },
  "softLandingDurationSeconds": 120,
  "setup": {
    "pinCreated": false,
    "onboardingComplete": false
  }
}
```

This adapts the user's “similar to” example: MEDIUM is no longer paired with STRONG numbers, credential metadata is complete when enrolled, authorization/session data is not stored, and saved consent is not trusted.

## Runtime-only entities

| Entity | Fields / lifecycle |
| --- | --- |
| ParentAccess | Volatile authenticated UI token, one-use grant permission, credential generation; cleared at child start, leaving parent UI, lock/off, PIN change, process restart |
| AuthorizationState | Authorization mode, generation, optional ChildGrant; starts at DULL on every process start, or DEACTIVATED when the valid saved `active` is false; DEACTIVATED holds no grant |
| ChildGrant | Selected duration D, ramp length L, start epoch/elapsed anchors, captured profile snapshot, completed pause duration P, optional pause-start elapsed value; projected epoch deadlines for parent display |
| CallState | ACTIVE / INACTIVE / UNAVAILABLE for the telephony source (one observation per active subscription, aggregated); monitor generation and minimal volatile observation timing; no caller identity, SIM identifier, or history |
| CallDetectionCapability | AVAILABLE / PERMISSION_MISSING / UNSUPPORTED / UNAVAILABLE, runtime-only; independent of VPN capability |
| CommunicationExclusions | Package names resolved at each VPN establish (default/system dialer, default SMS/RCS app, `com.whatsapp`) and per-package applied/skipped result; never persisted |
| EffectPolicy | Off, or enabled effects + captured intensity while DULL, Active and no ACTIVE call; derived by the reducer |
| EffectCycleState | Cycle start elapsed time and current strength fraction; reset on screen off, grant, call, Deactivate; never persisted |
| EffectCapability | Per effect: WORKING / ACCESS_MISSING / UNAVAILABLE; runtime-only |
| CompatibilityResult | Pass or one failure reason (NO_CONSENT, POLICY_BLOCKED, OTHER_VPN, FGS_NOT_ALLOWED, NO_TRAFFIC); runtime-only |
| VpnState | DISABLED / CONSENT_REQUIRED / STARTING / RUNNING / STOPPED / ERROR and current policy generation; coarse current reason only |
| SetupStatus | Derived missing credential, VPN consent, forwarding, notification/lifecycle eligibility or storage availability; call permission is not a setup blocker |
| PinRetryState | Consecutive failure count, elapsed-time retry-not-before; volatile only |
| ForwardingState | Bounded packet buffers and numeric flow mappings required for transport; no content decoding, history or export |

Clear buffers, runtime tokens and grant state when no longer needed. Aggregate pause time is current-session bookkeeping, not a record of individual calls. Runtime entities must not be added to the persisted schema without a spec and constitution review.

## Time accounting

Let D be selected play milliseconds, L ramp length, e0 the grant's elapsed start, e current elapsed time, P total completed union-of-call pauses, and a the optional current pause-start time.

- Paused-now amount is e−a when paused, otherwise 0.
- Consumed play C = (e−e0)−P−pausedNow.
- Remaining play R = max(0, D−C).
- Before accepting a new pause, evaluate whether C already reached D. An expired grant cannot be revived.
- Mode from consumed play: C < D−L → CHILD_PLAY_SESSION; D−L ≤ C < D → SOFT_LANDING; C ≥ D → DULL.
- While paused, C and ramp progress remain constant. Lock/unlock does not alter any accounting.
- The telephony source becoming ACTIVE starts the pause; it becoming INACTIVE/UNAVAILABLE closes it. Observations from several subscriptions or duplicate callbacks form one union interval, never multiple credits. Ringing-only, WhatsApp, and other app calls create no pause.
- On completed pause of length p, set P := P+p once.
- Display projections: expiry = startEpoch+D+P; rampStart = startEpoch+D−L+P. They are recomputed in memory and never stored.
- Wall-clock adjustment does not change e/e0/P. A regressed elapsed clock, overflow, or impossible negative accounting revokes to DULL.

The 30/5/25 example: D=30 minutes, elapsed since start=30 minutes, P=25 minutes, C=5 minutes, R=25 minutes. In Soft Landing, the same accounting preserves the remaining ramp rather than starting a fresh full ramp.

## Writes, migration and recovery

1. Load once through the repository. Missing file returns canonical defaults; do not infer enrollment.
2. Parse the explicit schema header and validate bounds before complete DTO decode; reject unknown v1 keys (except the legacy keys above) and invalid enum values or field relationships.
3. Corrupt/unsupported content invokes corruption replacement to safe defaults, with a transient parent repair reason. Do not preserve or log a raw broken document.
4. Actual filesystem I/O failure produces StorageUnavailable for that settings operation; the parent sees a save failure and the previous value stays in effect. Authorization is unaffected because it is never written.
5. Writes happen only for parent settings changes (Active/Deactivated, method switches and timings, PIN create/change, intensity, Soft Landing duration, VPN preference, setup flags) and for `brightnessRestore` (set once per DULL period, cleared on restore). Grants, pauses, expiry, and countdown never write.
6. v1 → v2 migration adds `active = true`, default `screenEffects` and `brightnessRestore = null`, and sets `schemaVersion = 2`; a v2 document missing `active` is corruption (safe defaults, which are Active). For future versions, add deterministic idempotent version-to-version migrations and tests from every shipped version. Migrations must not recursively access DataStore.
7. Keep settings in `noBackupFilesDir`, disable backup, exclude cloud and device transfer, and test transfer behavior.

## State relationships and validation references

[State-machine contract](contracts/state-machine.md) is normative for event ordering, grant boundaries and profile selection. [Android/VPN contract](contracts/android-vpn.md) controls capability reporting and exclusions. [Parent UI contract](contracts/parent-ui.md) controls disclosure and authentication.

No new cloud, database, analytics, history, notification content or call recording entities are introduced.

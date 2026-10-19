# Contract: SettingsRepository

**Layer**: app data boundary. One application-scoped instance/process is shared by UI and VPN components. Uses one typed DataStore JSON file as specified in [data-model.md](../data-model.md). Stores parent settings only; authorization and session state are never persisted.

## Contract

All operations are suspendable or observable Kotlin Flow operations; callers never read/write the backing file directly. Repository methods accept domain/settings DTOs, not Android Intent, Compose, or raw PIN strings.

| Operation | Input → output | Guarantees and failure behavior |
| --- | --- | --- |
| observeSettings() | none → Flow<SettingsSnapshot> | Emits one validated immutable settings snapshot. Contains no authorization or session data. |
| load() | none → Result<SettingsSnapshot> | Missing file returns canonical defaults (`active = true`). A v1 document migrates to v2 with `active = true`. Corrupt/unsupported content is replaced by safe defaults with a transient repair reason. Legacy `mode`/`childSession` keys are ignored and dropped on the next write. Storage failure is explicit. |
| createParentCredential(credential) | validated PinCredential → Result<SettingsSnapshot> | Atomically writes credential plus pinCreated flag. Raw PIN never accepted/stored. |
| updateActive(active) | Boolean → Result<SettingsSnapshot> | Atomic durable update of the Active/Deactivated setting (FR-031). Only SessionManager calls it, after an authenticated Activate or confirmed Deactivate. On write error the previous value stays in effect. |
| updateMethodSwitches(network, grey, volume, brightness) | Booleans → Result<SettingsSnapshot> | Atomic update; rejects a combination with all four false (InvalidValue); network maps to `vpnDullingEnabled`. |
| updateEffectTiming(buildUpSeconds, breakSeconds) | allowed values → Result<SettingsSnapshot> | Values outside the allowed sets rejected. |
| saveBrightnessRestore(value?) | BrightnessRestore or null → Result<SettingsSnapshot> | Called by ScreenEffectsController only: set once before the first brightness change, cleared after restore. |
| updateIntensity(intensity) | DullIntensity → Result<SettingsSnapshot> | Valid enum only, atomic durable update; a running session keeps its captured target. |
| updateSoftLandingDuration(seconds) | 60 or 120 → Result<SettingsSnapshot> | Other values rejected; current grant deadlines are not shifted. |
| updateVpnPreference(enabled) | Boolean → Result<SettingsSnapshot> | Stores a preference only, never live VPN consent/capability. SessionManager revokes authorization before acknowledging a stop. |
| updateSetupFlags(flags) | SetupFlags → Result<SettingsSnapshot> | pinCreated must agree with a valid credential. |
| changeCredential(expectedCredentialVersion, newCredential) | authenticated version + credential → Result<SettingsSnapshot> | Atomic update; stale version rejected; on write error the old credential stays valid. SessionManager revokes tokens/grants only after success. |

There are no authorization, grant, revocation, or call-pause operations. Grants, pauses, expiry, and countdown never touch storage.

Errors are typed as CorruptDocument (safe defaults/reset), UnsupportedSchema (safe defaults and parent explanation), InvalidValue (reject), StaleVersion (reject stale credential change), IoUnavailable (parent save failure; previous value stays in effect), and MigrationFailure (safe defaults). Exceptions and JSON bytes must not be surfaced/logged as diagnostics.

## Invariants

- One DataStore instance for the file; no multi-process access, direct file writes, per-tick updates or traffic-path storage.
- Top-level document ≤64 KiB; credential salt is 16 bytes and verifier 32 bytes after decoding; iteration count exactly 600000 for credential v1.
- `screenEffects` and `brightnessRestore` (nullable) are required in v2; at least one dulling method is enabled after setup completion.
- `active` is required in v2; defaults, corruption replacement and migration always produce `active = true`.
- `softLandingDurationSeconds ∈ {60,120}`; intensity ∈ {GENTLE,MEDIUM,STRONG}.
- Unknown keys other than the two legacy keys are corruption.
- No authorization mode, session timestamps, tokens, raw PIN, call record, communication-package resolution, URL/domain, network address, activity history, analytics or backup data.

## Contract verification

JVM repository tests use a temporary file and deterministic data. Verify atomic updates, one instance, missing/corrupt/future version, v1→v2 migration sets `active = true`, v2 missing `active` treated as corrupt, `updateActive` round trip and failed write, all-methods-off rejected, timing bounds, brightnessRestore set/clear, legacy-key drop, other unknown-key rejection, illegal combinations, interrupted writes, failed credential change keeps old credential, and migration idempotence. Check decoded field contents from synthetic fixtures only.

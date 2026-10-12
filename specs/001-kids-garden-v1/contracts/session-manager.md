# Contract: SessionManager

**Layer**: orchestration/domain boundary; pure policy comes from the Android-free reducer in [state-machine.md](state-machine.md).

## Public surface

| Operation / observable | Contract |
| --- | --- |
| state: StateFlow<ParentFacingSnapshot> | Read-only current authorization, derived setup/capability status, coarse VPN state, intensity and valid child remaining time. No child history, app traffic stats or identities. |
| dispatch(DomainEvent) | Accept typed platform/UI event and elapsed/epoch snapshot; serialize through one reducer owner; duplicate/stale events are idempotent or rejected. No raw PIN, Intent or content. |
| verifyParentPin(pinChars): Result<ParentAccess> | Delegates verification to ParentPinManager. Success yields volatile UI permission only; clears mutable PIN buffer best effort. Does not change phone authorization. |
| startParent(access, requestId): Result<AuthorizationSnapshot> | Consume fresh action permission, require interactive/unlocked device, setup/capability available and generation validation; commit in memory. Publish Normal only afterward. No storage write. |
| startChild(access, durationMinutes, requestId): Result<AuthorizationSnapshot> | Accept 10–120 minutes in 10-minute steps, require gates and fresh permission, capture selected target/ramp, start timer at commit, atomically clear ParentAccess/back-stack signal, commit the in-memory session before the normal profile. If the telephony source is ACTIVE, the session starts paused with the full selected grant. |
| exitParent(access): Result<Unit> | Reduce only access: revoke to DULL; no fresh PIN required for this revocation. Retain fixed communication-package exclusions. |
| onParentUiLeft/onLock/onScreenOff/onUserPresent | Clear UI token as specified; parent phone grant revoked on lock/screen off; child remaining time keeps running unless call-paused. |
| onCallAggregateChanged(observation, monitorGeneration, elapsed) | Consume the transient aggregated telephony ACTIVE/INACTIVE/UNAVAILABLE observation from CallStateMonitor. Freeze unexpired child/ramp time for the union of valid ACTIVE intervals; close once when no valid active source remains. Unknown/unavailable state continues countdown. Never change VPN readiness or globally suspend shaping. |
| onDeadlineCheck(elapsed) | Compute from absolute elapsed anchors, not a decrementing counter. A pre-pause boundary remains a mandatory CallStateMonitor fresh-state checkpoint. Cached ACTIVE cannot renew validity; INACTIVE/UNAVAILABLE/query failure closes the union pause and resumes accounting before projecting the next boundary. Resolve expiry before other stale events. |
| onSettingsInvalid/onProcessRestart/onReboot/onVpnCapabilityChanged | Fail safe: clear grants/tokens and return DULL. A permission/consent restoration never grants a session. |

`ParentFacingSnapshot` provides derived `displayMode` (including SETUP_REQUIRED), underlying `authorizationMode`, `vpnStatus`, `callCapabilityStatus` (independent of VPN readiness), `setupReason`, `dullIntensity` and nullable `remainingPlayMillis`. Parent authentication gates the full snapshot; default unauthenticated projection contains only the next permitted PIN/setup route.

## Ordering rules

1. Validate clock, state and expiry before granting or pausing.
2. Lock/screen-off/process restart/invalid state revocation wins over a concurrent Parent Mode start; a committed child grant survives lock.
3. Call beginning at zero child remainder does not create a pause credit. Call state does not add a grant or restore a revoked grant.
4. Duplicate/overlapping call indications form one union interval. Repeated end callbacks add no duplicate duration.
5. New explicit grants replace the previous grant. Child start clears parent navigation state immediately.
6. Authorization is memory-only; revocation immediately invalidates the generation. Settings writes run asynchronously and never gate lock/revoke processing.
7. A stale boundary callback cannot extend, expire-during-pause, or reinstate access. Any ambiguous sequencing fails to DULL.

## Error surface

Parent-facing errors are typed: PinMismatch, RetryDelayed(untilElapsed), SetupLimited(reasonCode), StorageUnavailable, InvalidGrant, and StaleRequest. Keep detailed reason behind PIN; no child dialogs, alarm, vibration, call confirmation, persisted retry counter or diagnostic record.

## Verification

JVM fake-time tests follow [state-machine.md](state-machine.md): 30 granted / 5 played / 25-minute phone call = 25 remaining; WhatsApp/other app calls never pause; 45-second ramp preservation; lock during/after pause; duplicate/overlapping callbacks; loss of detection/permission; stale generations; lost end callback recovered by fresh boundary revalidation; unsupported calls continue to expiry and do not block grants or dulling. Physical scoped call/exclusion qualification remains separate.

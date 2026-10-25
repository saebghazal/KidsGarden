# Contract: Authorization, Call Pause, and Dulling Policy

**Status**: Scoped V1 domain contract. Only normal mobile/Telecom calls pause child play; authorization is memory-only. Includes the parent Active/Deactivated switch (FR-031, 2026-10-15) and screen and sound dulling policy (FR-034–036, 2026-10-18).

## Public domain boundary

`TimeProvider` supplies epochMillis and elapsedRealtimeMillis snapshots. Domain uses elapsed time for duration and retry delays.

`StateMachine.reduce(state, event, time)` returns a new immutable state and typed effects. It imports no Android classes, UI, DataStore or VPN implementation. `SessionManager` serializes events and publishes StateFlow; all policy decisions belong to the reducer/pure DullModePolicy.

Incoming events: ProcessStarted(savedActive), ScreenOn, EffectCapabilityChanged, MethodSwitchesChanged, ActivateRequested, DeactivateRequested(confirmed), UiOpened, ParentUiLeft, PinVerified, PinRejected, StartParentRequested, StartChildRequested(duration), ManualParentExit, ScreenOff, DeviceLocked, UserPresent, CallAggregateChanged, CallCapabilityChanged, DeadlineObserved, SettingsChanged, SettingsInvalid, SettingsWriteSucceeded/Failed, VpnCapabilityChanged, RebootObserved and PackageReplaced.

Do not pass raw PINs or Android Intent extras into the reducer. PIN verification returns only an opaque short-lived permission/generation. Call events contain minimal active/unknown state and timing, not identities or content.

Effects: ApplyEffectPolicy(policy, generation), PersistSettings (parent settings changes only, including `active` and method switches; never authorization, session, or pause data), StartVpn, StopVpn, ApplyNetworkPolicy, ScheduleBoundary, CancelBoundary, ClearParentUiAccess, PublishParentStatus. Each asynchronous effect carries an authorization generation. Ordinary UI navigation is not a phone authorization effect.

## State dimensions

- `AuthorizationMode` (in `Mode.kt`): DULL, PARENT_MODE, CHILD_PLAY_SESSION, SOFT_LANDING, DEACTIVATED. `DisplayMode` adds SETUP_REQUIRED (derived only).
- Live, memory-only authorization uses the first five; DEACTIVATED is the parent-chosen off state and holds no grant; SETUP_REQUIRED is a derived parent view. Nothing about authorization is read from or written to storage.
- ParentAccess is independent of phone authorization.
- A validly ACTIVE telephony call source preserves authorization unless separately revoked and pause child play/ramp accounting. They do not override the network policy.
- VPN/storage/setup unavailability still follows existing grant failure rules. Call permission/observation unavailability only removes pause eligibility and shows the parent limitation; it does not block grants or working VPN dulling.
- Call state is ACTIVE / INACTIVE / UNAVAILABLE per scoped source, transient and versioned. INACTIVE describes only that source, never universal call absence.

## Event contract

| Event and preconditions | Authorization / accounting | Network intent / UI |
| --- | --- | --- |
| ProcessStarted, reboot, update | DULL with no tokens or grant (memory-only state starts empty); DEACTIVATED instead only when valid loaded settings have `active = false` | No stale Normal authorization; apply Dull only when safe capability exists; while DEACTIVATED do not start the VPN |
| Mode becomes DULL, Active, no ACTIVE call | EffectPolicy = enabled effects + current intensity | ApplyEffectPolicy; cycle starts at zero on the next ScreenOn or immediately if the screen is on |
| Grant start, Deactivate, telephony ACTIVE, or SOFT_LANDING/CHILD/PARENT mode | EffectPolicy = Off | ApplyEffectPolicy(Off): overlay removed, volume cap released, brightness restored |
| ScreenOff while EffectPolicy on | Policy unchanged | Cycle stops; restarts from zero on ScreenOn |
| EffectCapabilityChanged / MethodSwitchesChanged | Recompute dulling capability = VPN running OR any working enabled effect | SETUP_REQUIRED presentation only when capability is none; a working effect lets DULL hold and grants be offered |
| DeactivateRequested(confirmed) + valid ParentAccess, any state except DEACTIVATED | After SettingsWriteSucceeded(active=false): DEACTIVATED; clear grant, pause accounting and grant permissions; bump generation | StopVpn and foreground service; dashboard shows Deactivated. On write failure: state unchanged, StorageUnavailable |
| ActivateRequested + valid ParentAccess, DEACTIVATED | After SettingsWriteSucceeded(active=true): DULL with no grant | StartVpn; Dull when capability exists, otherwise SETUP_REQUIRED presentation. On write failure: stay DEACTIVATED |
| Any grant, lock, screen, call, boundary, VPN or capability event while DEACTIVATED | Ignored; stays DEACTIVATED | No network policy; VPN stays stopped |
| Ordinary UiOpened | Reconcile existing live state; no grant | PIN screen or setup; opening UI is not process restart |
| UiOpened during PARENT_MODE, unlocked, no unused permission | Issue ParentAccess with a fresh one-use grant permission; mode unchanged | Parent actions without PIN; Normal unchanged |
| Valid PIN | ParentAccess only | No policy change |
| StartParent + fresh grant permission + unlocked + valid setup/capability | Commit PARENT_MODE in memory; consume grant permission | Normal after generation/interactive/capability recheck |
| StartChild(10–120, step 10) + same prerequisites | One new grant; clear all ParentAccess as part of commit | Normal; brief noninteractive confirmation; protected back stack cleared |
| Invalid duration / stale permission / duplicate command | No new grant | Calm parent error, no extra duration |
| ScreenOff or DeviceLocked | Parent mode/token revoked; child counts elapsed time unless validly call-paused | Dull intent for revoked parent; fixed package exclusions remain |
| UserPresent in DULL, Unlock grace on, Active, setup complete, a dulling method works, rest time over (FR-037) | Start an in-memory unlock-grace grant (D = grace + fade, L = fade); save its start time | Normal, then the Soft Landing fade to DULL; no child-facing effect |
| UserPresent | Revoke any surviving parent grant defensively; evaluate child accounting | DULL stays DULL; child/ramp continues only when valid |
| Telephony source becomes ACTIVE | Preserve parent/DULL; freeze unexpired child C and ramp progress | Retain current policy for non-excluded traffic; no confirmation |
| Additional/duplicate active indication | Existing union pause, no second credit | Current policy remains |
| Telephony source becomes INACTIVE or UNAVAILABLE | Close pause once; resume remaining child/ramp time; no grant → DULL | Resume computed policy silently; unavailable detection shows parent limitation |
| Call end after lock-revoked parent, invalidity, replacement or restart | Never revive prior grant | Use latest validated state/capability |
| Child request while telephony source is ACTIVE | New selected grant starts paused; no inherited remainder | Apply new grant policy; immediately PIN-protect UI |
| Boundary outside call pause | At D−L enter ramp; at D expire and discard the grant | Updated staged profile/Dull |
| Stale boundary during validated call pause | Revalidate observable active state; ignore obsolete deadline only while valid positive time is frozen | Retain saved policy, no global Normal override |
| Call starts at zero remaining play | Already DULL; no revival or pause credit | DULL with fixed package exclusions |
| Wall time/timezone changes | No extra play; elapsed accounting remains | No new authorization |
| Invalid elapsed state or accounting | DULL, clear grant/tokens | Safe capability-dependent intent; parent-only current error |
| ManualParentExit | DULL without additional PIN | DULL with fixed package exclusions |
| PIN change | Verify current PIN, persist new credential; revoke every token/grant | DULL |
| VPN revoke/forwarding failure | DULL; no stale grant restored by repair | Unshaped/bypass or stopped VPN, parent SETUP_REQUIRED |
| Call permission loss/adapter failure/unsupported observation | End affected source eligibility; if no active source remains, close pause and continue countdown | Keep current authorization/VPN health; exact parent-only limitation |
| Ringing only, WhatsApp or other app call, or undetectable call | No pause; countdown and expiry continue | No policy override, no implementation blocker |
| Ordinary offline/reconnect with healthy capability | No reset/pause other than independently active call | Recompute current policy, not stored previous policy |

## Concurrency and effect acknowledgments

The reducer receives serialized events; native/VPN effects run outside its event loop. Grants commit in memory within one reducer step: verify the fresh grant permission, interactive/unlocked state, and live capability, then bump the generation and publish Normal. No disk write is involved, so there is no pending-grant state.

Revocation invalidates the generation immediately and removes any cached unrestricted policy; stale VPN acknowledgments for an old generation have no authority. Settings writes (PIN, intensity, ramp, VPN preference, setup flags) run asynchronously and report SettingsWriteSucceeded/Failed; they never block lock/revoke processing and never change authorization except that a successful PIN change revokes all grants.

Expiry/invalidity is evaluated from the event's trusted elapsed observation before accepting new operations. For simultaneous child expiry and call arrival, no pause credit is applied to an already exhausted grant. New explicitly authenticated grants are still possible afterward; they never reuse an old token.

Bound/collapse redundant tick/profile observations; never drop security-relevant lock/revoke events silently. If authorization ordering/queue overflow becomes unclear, invalidate authorization. Unavailable call observations alone resume countdown without disabling shaping. Reject stale monitor generations. Preserve the pre-pause next boundary as a mandatory fresh-state checkpoint; cached ACTIVE cannot renew validity. Lost registration, permission/query failure, monitor restart, or fresh INACTIVE/UNAVAILABLE closes the affected source and resumes countdown when no current active source remains.

## Profile mapping and precedence

0. DEACTIVATED → VPN stopped and EffectPolicy Off; ordinary routing. This is intentional, not a capability failure or SETUP_REQUIRED.
1. Unavailable forwarding capability → ordinary fail-open routing and parent-only limited VPN status; this is not successful DULL enforcement.
2. Fixed exclusions (default/system dialer, default SMS/RCS app, com.whatsapp) bypass the VPN where technically possible, independently of call detection. All remaining traffic follows authorization below.
3. PARENT_MODE or early child play → Normal.
4. SOFT_LANDING → target-relative ramp.
5. DULL → selected dull profile.

A display mode of SETUP_REQUIRED never authorizes Normal. “No throttle because the app cannot safely shape” must be distinguishable from authorized Parent Mode.

Rates: decimal kbps aggregate per direction, rateBytesPerSecond = kbps×1000/8. Latency: added RTT, half each direction. Normal means no intentional cap/delay.

For a session target K kbps / L ms and ramp fraction p:
- Start p=0 is Normal.
- First and second stages use p=1/3 and 2/3.
- At p=1 use target.
- Conceptual intermediate cap K/p and additional delay p×L; never exceed target severity.
- Within each stage the engine may smooth scheduling, but it must not overshoot the selected target.
- Gentle must not pass through Medium; Strong's initial target is 1000 ms.
- Call pause freezes p; on resume restore that stage/progress. Do not replay elapsed stages.
- New intensity during an active grant updates the saved preference for afterward, not its captured target.
- An authorized Normal transition cancels artificial delay without resetting connections or replacing the grant. A call pause alone retains the current ramp/DULL policy.

## Minimum contract tests

Unauthorized unlock; PIN without action; each duration; child immediate UI lock; parent lock/off/unlock; UI recreate versus process death; app update/reboot starting at DULL; invalid elapsed accounting; wall clock shifts; stale commands; concurrent expiry/call; multi-subscription/duplicate calls; WhatsApp/other app call produces no pause; missed calls; lost end callback at mandatory boundary; paused session lock/unlock; parent lock during call; replacement during call; 30/5/25 example; paused 45-second ramp; unsupported/unavailable call observation; VPN loss; failed settings write leaves authorization unchanged; deactivate from every state (including during a call pause and Soft Landing); every event ignored while DEACTIVATED; activate to DULL and to SETUP_REQUIRED; failed Activate/Deactivate write; process start with saved `active = false`; EffectPolicy on only in DULL+Active without ACTIVE call; policy Off on every grant/call/Deactivate; capability with VPN failed but an effect working; each target-relative profile and explicit Gentle non-overshoot.

Use fake elapsed time and scoped observations for domain tests. Verify unsupported calls, permission loss and adapter failure continue countdown without blocking grants/dulling; lock/unlock does not reset paused time. Device tests separately qualify actual telephony observation and communication-app exclusions; fakes do not prove platform behavior.

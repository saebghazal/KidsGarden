# Feature Specification: Kids Garden V1 — Dull Mode, Parent Mode, and Child Play Sessions

**Feature Branch**: `master` (existing branch; no branch hook configured)

**Created**: 2026-10-10

**Status**: Draft — scoped V1 call handling and the parent Active/Deactivated switch defined; Phase 1 Setup and Phase 2 Foundational can start. Implementation and device qualification remain pending.

**Input**: User description: "Kids Garden V1: Android is dull by default; a parent PIN authorizes untimed parent use or 10, 20, or 30 minutes of child play, ending with a quiet Soft Landing. Local settings only, no content collection, no logs, and honest best-effort VPN dulling."

## Clarifications

### Session 2026-10-10

- Scope correction (2026-10-10): The earlier universal-call requirement and device-wide call override are superseded. V1 supports normal mobile/system/Telecom calls where Android APIs and permissions allow, plus required best-effort WhatsApp handling (later narrowed to VPN exclusion only, with no play-time pause; see the audio-mode clarification below). Universal detection is not a prerequisite for implementation.
- Q: If a detectable active call ends during Parent Mode or an unexpired child session, should authorization continue? → A: Preserve valid authorization: Parent Mode continues unless separately revoked; child play resumes with the same remaining time. A 30-minute session used for 5 minutes still has 25 minutes after a 25-minute detectable active call. DULL remains active for non-excluded traffic throughout calls. All transitions are silent.
- Q: Should starting a child session immediately lock access to the parent controls? → A: Yes. Starting a child session immediately clears parent-interface access; the PIN is required again for all parent controls, with no separate handover step (Option A).
- Q: When Android only reports that some app is in a voice/video call (audio mode) without identifying the app, should that pause the child's play timer? → A: No (Option A). Only normal mobile/Telecom calls pause play time. Third-party app calls, including WhatsApp, never pause the timer; `com.whatsapp` stays excluded from VPN dulling so its calls are not slowed. Rationale: the generic signal cannot distinguish WhatsApp from game voice chat, and app-identifying APIs (notification listener, Accessibility, Usage Access) are prohibited.
- Q: Should the phone's default text-messaging app (SMS/RCS) also be excluded from dulling, like the Phone app and WhatsApp? → A: Yes (Option A). Exclude the current default SMS/RCS app, identified only by package name through the public default-SMS API, with no message access. It does not pause play time.
- Q: Which languages must the V1 parent screens and the neutral notification text support? → A: English only for V1 (Option A). All user-visible text lives in Android string resources so later localization needs translation, not code changes.
- Q: Should the current mode and child-session timestamps be saved to storage at all, given every restart discards them? → A: No (Option A). Authorization mode and child-session timing are held in memory only. The settings document holds only parent settings, the PIN credential, and setup flags.

### Session 2026-10-12

- Q: How long must the parent PIN be? → A: One to six ASCII digits (owner decision for this version). Shorter PINs are easier to guess; the five-failure, 30-second retry delay still applies.
- Q: What happens after “Use phone as parent”? → A: No confirmation window. Once PARENT_MODE commits, Kids Garden moves to the background so the parent can use the phone at once.
- Q: Is the PIN required to open Kids Garden while PARENT_MODE is active? → A: No. While PARENT_MODE is active and the phone is unlocked, opening Kids Garden shows the parent actions directly, with “Exit Parent Mode” in place of “Use phone as parent” and a fresh one-use grant permission (so “Give phone to child” works without a PIN). Outside PARENT_MODE the PIN is required as before.
- Q: Should locking still end PARENT_MODE? → A: Yes (unchanged). Screen off or any lock ends PARENT_MODE immediately; within PARENT_MODE network behavior stays Normal with no DULL moment.
- Q: Which child durations? → A: 10 to 120 minutes in 10-minute steps, chosen with up/down arrows (starting at 10) and confirmed with a Start button.

### Session 2026-10-15

- Q: Can the parent switch Kids Garden off without uninstalling it? → A: Yes. A PIN-protected **Active / Deactivated** switch. While Deactivated, Kids Garden never enters DULL: VPN dulling stops, any Parent Mode or child grant ends, and the phone behaves normally with no timer. The choice is a saved setting, so it survives app restart, update, and reboot. Activating returns to DULL at once (or SETUP_REQUIRED if dulling cannot start); normal use then needs PIN plus an action as before. New installs and reset settings start Active.

### Session 2026-10-25

- Q: Should an unlock without the PIN ever give normal use? → A: Yes, as a parent-configured Unlock grace (FR-037), on by default: 5 minutes, 2-minute fade, then a 1-hour rest during which unlocks stay dull. The parent tells the child the phone is "tired"; the app shows nothing.

### Session 2026-10-18

- Q: What if a phone cannot run the local VPN (another VPN in use, a device policy blocking VPNs, the phone maker's battery saver stopping it, or the parent turning it off)? → A: Every Android 15/16 phone includes VpnService, but it can still be blocked. Add a phone compatibility check during setup that confirms locally that traffic really passes through Kids Garden and tells the parent either “This phone can be dulled” or the specific reason it cannot. When Android stops Kids Garden in the background, show the parent optional battery-saver guidance. No other dulling method exists in V1; a phone that fails the check is honestly reported as unprotected. (Superseded in part by the next answer: screen and sound dulling now also works without the VPN.)
- Q: How can Dull Mode work when the VPN is not an option? → A: Add three parent-configurable screen and sound effects that apply only in DULL: a grey screen tint, lower media volume, and lower screen brightness. Each can be turned on or off in Settings. They do not switch on at once: each effect fades in gradually (default over two minutes), then the phone returns to normal for a break (default one minute), and the cycle repeats while DULL lasts. They work with or without the VPN, so a phone that fails the compatibility check can still be dulled.
- Q: Can each dulling method be enabled or disabled? → A: Yes. Network dulling (VPN), Grey screen, Lower media volume and Lower brightness each have an on/off switch in Settings; at least one stays on, and turning everything off is done with Deactivate.
- Q: During the break, does media volume and brightness come back? → A: Yes. The volume cap is lifted, so the child may turn volume up again, but Kids Garden never turns volume up by itself. Brightness eases back to the parent's saved level during each break; the saved level is cleared only when the effects stop (leaving DULL, a call, Deactivate, or the switch turned off).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Set up a phone that stays dull by default (Priority: P1)

As a parent, I can install Kids Garden, create a private PIN, understand its limitations, and enable dulling so ordinary device unlock does not grant normal-use authorization.

**Why this priority**: Dull-by-default behavior is the product's foundation.

**Independent Test**: Complete fresh setup on a supported phone, compare network responsiveness, and repeatedly unlock without entering the Kids Garden PIN.

**Acceptance Scenarios**:

1. **Given** a supported phone, **When** the parent installs the free private APK, **Then** welcome, PIN creation, privacy/limitations explanations, and explained VPN consent are available without an account or factory reset.
2. **Given** matching valid PIN entries, **When** setup saves, **Then** only a secure credential representation is retained, never a raw PIN.
3. **Given** complete setup and working VPN dulling, **When** no authorization exists, **Then** DULL applies the selected profile; network-heavy apps still open without Kids Garden blocking screens.
4. **Given** DULL, **When** the device is unlocked with its normal credentials, **Then** it remains DULL.
5. **Given** declined VPN consent or interrupted setup, **When** the parent returns, **Then** setup is resumable, SETUP_REQUIRED explains limited protection, and no normal-use grant has been created.
6. **Given** VPN consent granted during setup, **When** the compatibility check runs, **Then** the parent sees either “This phone can be dulled” or a specific reason (another VPN is active, a device policy blocks VPNs, VPN consent missing, or traffic did not pass through), and setup completes only after a passing check.
7. **Given** another VPN app or a device policy blocking VPNs, **When** setup or a re-check runs, **Then** the result names that cause and what the parent can do, no protection is claimed, and the parent can re-run the check after fixing it.

### User Story 2 - Use the phone as a parent until it locks (Priority: P1)

As a parent, I can explicitly authorize untimed normal use and know that locking ends it.

**Why this priority**: Parent convenience must not leave unrestricted authorization available after a locked phone is handed to a child.

**Independent Test**: Start Parent Mode from DULL, use another app, and exercise screen-off, manual lock, automatic lock, and unlock separately.

**Acceptance Scenarios**:

1. **Given** normal device unlock while DULL, **When** the parent enters the Kids Garden PIN, **Then** parent actions appear but DULL continues until an action is chosen.
2. **Given** valid PIN access, **When** “Use phone as parent” is selected, **Then** PARENT_MODE starts with no timer and no intentional throttle, and Kids Garden moves to the background without a confirmation window.
3. **Given** PARENT_MODE, **When** the parent uses another app with the screen on and unlocked, **Then** Parent Mode continues.
4. **Given** PARENT_MODE, **When** the screen turns off or the device locks, **Then** authorization ends immediately; wake/unlock remains DULL.
5. **Given** PARENT_MODE, **When** manual exit, invalid state, or owning-process restart occurs, **Then** DULL applies and a fresh PIN/action is required to start again.
6. **Given** PARENT_MODE, **When** the parent reopens Kids Garden before the phone locks, **Then** parent actions appear without a PIN, offering “Exit Parent Mode” and “Give phone to child”; the parent-actions text explains that locking ends Parent Mode.

### User Story 3 - Give the child a fixed play session (Priority: P1)

As a parent, I can grant a play duration that continues to elapse while the phone is locked, offline, or Kids Garden is not visible, but pauses during detectable active mobile/Telecom calls so the child keeps their remaining play time.

**Why this priority**: Ordinary phone actions must not bypass the time limit.

**Independent Test**: Start each allowed duration and exercise repeated lock/unlock and deadline boundaries using controlled time, followed by real-device verification.

**Acceptance Scenarios**:

1. **Given** a correct PIN and “Give phone to child,” **When** a duration from 10 to 120 minutes (10-minute steps) is selected and started, **Then** one in-memory session records startedAt, softLandingStartsAt, and expiresAt; normal or near-normal behavior begins and parent-interface access is immediately cleared. A brief parent-facing confirmation grants no access to controls and requires no separate handover step.
2. **Given** a 20-minute session without a call interruption, **When** five total minutes have elapsed including locked time, **Then** 15 minutes remain; every subsequent locked minute outside a validated call pause reduces that remainder.
3. **Given** a valid session before Soft Landing, **When** repeated lock/unlock occurs, **Then** CHILD_PLAY_SESSION continues with unchanged deadlines.
4. **Given** expiry while locked, **When** the phone wakes/unlocks, **Then** DULL applies without a new session or unrestricted grace period.
5. **Given** an existing grant, **When** the parent enters the PIN again and explicitly starts another allowed session, **Then** it replaces the old grant; durations do not stack and stale confirmations cannot extend access.
6. **Given** a 30-minute session with 5 minutes used, **When** an active mobile/Telecom call is detectable for 25 minutes and ends, **Then** 25 minutes of child play remain and resume automatically without confirmation. The original expiry moves later by exactly the call pause, not by a new 30-minute grant.
7. **Given** a child session paused for a call, **When** the phone locks/unlocks or a second overlapping detectable active call begins, **Then** the remaining play time stays frozen until no detectable active mobile/Telecom call remains. Overlapping calls count as one continuous pause, not multiple credits.
8. **Given** a child session has just started, **When** the child uses Back, reopens the app, taps a notification, or follows a link to parent controls, **Then** dashboard, Settings, and grant actions require fresh PIN entry; no previously authenticated screen can restore access. This parent-interface lock does not end or alter the child grant.

### User Story 4 - End play through a quiet Soft Landing (Priority: P1)

As a child using the phone, network entertainment gradually becomes less responsive without Kids Garden confronting me.

**Why this priority**: Avoiding conflict is a required behavior, not optional styling.

**Independent Test**: Observe a session's final interval and every app-controlled visible surface while measuring shaping with synthetic traffic.

**Acceptance Scenarios**:

1. **Given** a valid session outside a call pause, **When** the effective softLandingStartsAt is reached, **Then** SOFT_LANDING gradually moves from Normal toward the session's dull profile.
2. **Given** Soft Landing, **When** the device locks/unlocks before expiry, **Then** the ramp continues at its elapsed-time position, never restarting.
3. **Given** the effective expiresAt outside a call pause, **When** time reaches that boundary, **Then** DULL applies, active timestamps are cleared, and no blocking screen, countdown warning, sound, vibration, or “time is over” message appears.
4. **Given** a delayed wake that skips part or all of the ramp, **When** state is evaluated, **Then** elapsed play time excluding validated call pauses determines the correct state; a missed callback never extends access.
5. **Given** SOFT_LANDING with 45 seconds remaining, **When** a detectable active mobile/Telecom call interrupts it and ends, **Then** those 45 seconds remain and the ramp resumes from its saved progress without confirmation or replaying the full ramp.

### User Story 5 - Manage settings and see actual protection (Priority: P2)

As a parent, I can privately inspect mode and VPN status, adjust settings, and understand V1's limits.

**Why this priority**: Accurate status and simple controls prevent misplaced trust.

**Independent Test**: Authenticate to Settings, change each setting, reopen the app, and simulate revoked consent and service failure.

**Acceptance Scenarios**:

1. **Given** an unauthenticated app launch or notification tap, **When** parent information/actions are requested, **Then** the PIN is required before dashboard, settings, or grant controls appear.
2. **Given** a wrong PIN, **When** verification fails, **Then** a calm parent-facing error appears and no setting or authorization changes.
3. **Given** authenticated Settings, **When** PIN, intensity, or Soft Landing duration changes, **Then** valid changes persist locally; the old PIN ceases to work after a successful change.
4. **Given** the dashboard, **When** state changes, **Then** mode, actual VPN status, intensity, setup status, and any nonnegative session remainder update accurately.
5. **Given** revoked VPN consent or another VPN taking over, **When** detected, **Then** Kids Garden cancels authorization and shows parent-only SETUP_REQUIRED/limited protection; a saved enabled flag never proves working dulling.
6. **Given** limitations/privacy screens, **When** read, **Then** they explain normal installation, uninstall exposure, best-effort network dulling, no full OS control/total app blocking, future Device Owner/DPC protection, and no content/logs collection.
7. **Given** Parent options during any mode, **When** the parent turns the switch off, **Then** any grant ends, VPN dulling stops, the dashboard shows Deactivated, and the phone stays undulled through lock/unlock, restart, and reboot.
8. **Given** DEACTIVATED, **When** the parent enters the PIN and chooses “Activate Kids Garden,” **Then** DULL applies (or SETUP_REQUIRED if dulling cannot start) and no normal-use grant exists until a fresh PIN plus action.
9. **Given** Grey screen, lower volume and lower brightness enabled with default timings, **When** the phone is DULL with the screen on, **Then** each effect rises smoothly over two minutes, returns to normal for one minute, and repeats; no text or blocking screen appears and touches work throughout.
10. **Given** effects running, **When** the parent starts Parent Mode or a child session, a phone call becomes active, or the screen turns off, **Then** all effects end promptly and brightness returns to the parent's previous setting; the next DULL period starts again from no effect.
11. **Given** “Display over other apps” or “Modify system settings” access is denied or revoked, **When** the parent opens the dashboard, **Then** only that effect shows as not working, and other effects, VPN dulling and grants are unaffected.

### User Story 6 - Recover safely without surveillance records (Priority: P1)

As a parent, I want failures to invalidate authorization while preserving privacy and essential phone use.

**Why this priority**: Errors must neither grant normal use nor create records of the child's activity.

**Independent Test**: Inject malformed settings and deadlines, change clocks, terminate the process, reboot, and inspect app-owned storage/output in a controlled lab.

**Acceptance Scenarios**:

1. **Given** missing/corrupt/unsupported settings or invalid timing, **When** loaded, **Then** DULL and safe defaults apply; invalid credentials require enrollment again.
2. **Given** any active grant, **When** its owning process restarts, the app updates, or the device reboots, **Then** all authorization and current child timestamps are discarded; valid settings survive.
3. **Given** a session, **When** wall time changes in either direction, **Then** no access is extended or revived; unverifiable elapsed timing ends the grant.
4. **Given** setup, use, wrong PINs, and recovery, **When** app-owned data/output are inspected, **Then** only allowed settings exist in storage (no mode or session data), with no raw PIN, content, destinations, history, logs, or telemetry.
5. **Given** any mode, **When** the VPN is configured, **Then** known system Phone/Dialer packages, the current default SMS/RCS app, and WhatsApp (`com.whatsapp`) are excluded from dulling where technically possible; Dull Mode continues for the rest of the device according to current authorization. Verify exclusions without live emergency calls.
6. **Given** a WhatsApp or other third-party app call, or any undetectable call, **When** a child session is running, **Then** countdown and normal Soft Landing/expiry continue; this does not block V1 implementation or disable otherwise working dulling. WhatsApp traffic remains excluded from dulling.
7. **Given** missing/unavailable call detection permission, **When** a child session runs, **Then** no call pause occurs; show only the parent-facing limitation: “Play time pauses during normal phone calls. Calls in other apps, such as WhatsApp, are not slowed down but do not pause play time.”
8. **Given** a child session paused for a detectable active mobile/Telecom call, **When** the call ends or observation becomes unavailable, **Then** pause accounting closes once and countdown resumes from the remaining time; no new grant or global network bypass is created.

### Edge Cases

| Condition | Required outcome |
| --- | --- |
| Screen off without lock; lock while screen remains on | Either ends Parent Mode; neither changes child deadlines. |
| Device without a secure device lock | Screen off still ends Parent Mode; Kids Garden PIN remains required. |
| UI recreation or leaving the parent interface | Parent UI reauthenticates on reopening; healthy domain authorization follows mode rules. UI recreation alone is not process restart. |
| Deactivate during a child session, Soft Landing, or call pause | The session ends silently with no history; network becomes Normal; no child-facing message. |
| Another VPN active, device/work policy blocks VPNs, or foreground service not allowed at setup | Compatibility check fails with that reason; setup stays incomplete; no protection claimed; parent can fix and re-run. |
| VPN cannot run but screen/sound effects are enabled | DULL works through the effects only; dashboard says the network is not dulled; grants are offered. |
| Child raises media volume or brightness during an effect | The cap is re-applied within the current cycle; no message is shown. |
| Process dies while brightness is lowered | On the next start, the saved previous brightness and automatic setting are restored before any new cycle. |
| Phone maker's battery saver stops Kids Garden in the background | Next process start finds the VPN not running with the preference enabled; DULL authorization with SETUP_REQUIRED and the FR-033 message and optional guidance. |
| Activate while VPN consent was revoked or another VPN took over during DEACTIVATED | DULL authorization with SETUP_REQUIRED; no grant is created. |
| Settings save fails while switching Active/Deactivated | Previous state stays in effect; parent sees a calm error. |
| Duplicate start taps; concurrent lock, expiry, or PIN acceptance | At most one valid explicit action commits one grant. Invalidity/expiry is resolved first; stale events cannot revive old state. |
| Exactly effective softLandingStartsAt or expiresAt outside a call pause | The former belongs to SOFT_LANDING, the latter to DULL. |
| Time zone, daylight-saving, or wall-clock adjustment | Elapsed duration including sleep, excluding validated call pauses, governs child access; calendar changes cannot add play time. |
| Negative, missing, overflowed, unordered, future-inconsistent timestamps or unsupported duration | Reject the session; never guess a longer deadline. |
| Failed/partial settings write or migration | No new authorization from incomplete data; recover DULL. |
| Saved VPN-enabled flag but stopped, starting, revoked, or faulty service | Show actual capability as limited/unavailable, not active protection. |
| Force-stop, uninstall, app-data clearing, OS termination, or user-stopped service | Dulling may cease outside app control; next supported execution is DULL with setup if needed. |
| Reboot before first unlock | No grant resumes; no claim of shaping before service/settings are available. |
| Offline, airplane mode, Wi-Fi/cellular switch, captive portal | Time keeps running unless a call pause is independently active; reconnection cannot refresh authorization. |
| IPv6, UDP/QUIC, unsupported traffic, or another profile | Qualify traffic coverage; never silently claim unhandled traffic is protected. |
| Another VPN | Explain conflict to parent; do not promise simultaneous control of the same VPN slot. |
| Cached video, downloaded content, offline games | May remain enjoyable; no cache erasure or app blocking. |
| Forgotten PIN | No cloud recovery or secret bypass. Help explains clear-data/reinstall and fresh setup, with loss of settings/protection. |
| Credential corruption | No default/empty-PIN unlock; invalidate grants and require setup. |
| Mobile/Telecom calls and WhatsApp | Pause only while an active mobile/Telecom call is detectable. WhatsApp and other app calls never pause. Exclude known Phone/Dialer packages and com.whatsapp from dulling where possible; no universal detection or call-protection claim. |
| Text messages (SMS/RCS) | Plain SMS is unaffected by the VPN. The current default SMS/RCS app is excluded from dulling, so RCS chat and media messages are not slowed; messages are never read and never pause play time. If the default SMS app changes, the new package is excluded the next time the VPN is established. |
| Generic audio-mode "in communication" signal (any VoIP or game voice chat) | Not used for pausing; it cannot identify the app and would allow unlimited play during game voice chat. |
| Ringing-only, missed/declined, waiting, or overlapping calls | Ringing alone creates no pause. Detectable active calls form one union pause; resume when no active mobile/Telecom observation remains. No duplicate credit or device-wide bypass. |
| Previously calculated child deadline passes during a validated call pause | Perform the mandatory fresh-state checkpoint. Fresh ACTIVE may establish only the next projected boundary checkpoint; INACTIVE/UNAVAILABLE/query failure closes the pause once, shifts deadlines by the validated pause, and resumes the remainder. Cached state cannot create an indefinite pause. |
| Lock, invalid state, restart, or parent action during a call | Existing revocation rules still apply: lock/screen off ends Parent Mode; restart/invalid state ends grants. Call end cannot revive a revoked or replaced authorization. Lock alone does not cancel a paused child grant. |
| Call starts at the same instant child play time is exhausted | Zero remaining time means DULL; fixed package exclusions remain but cannot revive an expired session. |

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001 — Default**: While Kids Garden is Active (FR-031), no valid Kids Garden authorization MUST mean DULL. Device unlock, app launch, successful PIN alone, notification taps, and reconnect MUST NOT grant normal use, except the parent-configured Unlock grace (FR-037).
- **FR-002 — Modes**: Expose DULL, PARENT_MODE, CHILD_PLAY_SESSION, SOFT_LANDING, DEACTIVATED, and parent-only SETUP_REQUIRED. SETUP_REQUIRED is a capability/setup status over underlying DULL authorization, never a normal-use grant. DEACTIVATED is the parent-chosen off state from FR-031; it is never entered by any event other than an authenticated Deactivate action or a saved Deactivated setting at process start.
- **FR-003 — Setup**: Provide welcome, PIN creation/confirmation, local-only/privacy and normal-app limitations explanations, and explained VPN consent. Incomplete setup MUST be resumable without granting normal use.
- **FR-004 — Parent gate**: After initial enrollment, require the Kids Garden PIN for parent actions, dashboard, and settings. Exception: while PARENT_MODE is active and the phone is unlocked, opening Kids Garden restores parent-interface access with a fresh one-use grant permission and no PIN. Device credentials MUST NOT substitute. Clear parent-interface access immediately when a child grant starts, and on lock, screen off, process restart, or leaving that interface. After child-session start, Back navigation, cached screens, notification taps, and links MUST NOT expose parent controls without fresh PIN entry. Clearing parent-interface access does not cancel the child grant; phone authorization follows separate mode rules.
- **FR-005 — Credential**: Never store/log raw PINs. Store a securely salted, computationally hardened verification representation. Changing PIN requires the current PIN and confirmation of the new one; failed changes MUST NOT accidentally replace a valid credential.
- **FR-006 — PIN UX**: Use a numeric PIN of one to six digits, masked entry, creation/change confirmation, and calm wrong-PIN error such as “That PIN did not match. Try again.” Five consecutive failures cause a 30-second in-memory retry delay, with no persisted attempt record. This is best-effort guessing resistance, not tamper protection.
- **FR-007 — Actions**: After PIN acceptance, show “Use phone as parent,” “Give phone to child,” and “Settings.” While PARENT_MODE is active, “Exit Parent Mode” replaces “Use phone as parent.” While DEACTIVATED, “Use phone as parent” and “Give phone to child” are hidden and “Activate Kids Garden” is shown with “Settings.” Each new normal-use grant requires fresh PIN entry plus an explicit action, consuming that pending grant permission. Abandoning duration selection MUST NOT start a session.
- **FR-008 — Parent use**: Starting PARENT_MODE MUST apply Normal network behavior without a timer and then move Kids Garden to the background with no confirmation window; the parent-actions screen shown while PARENT_MODE is active states that locking ends it. Switching to another app while screen-on/unlocked MUST NOT end Parent Mode.
- **FR-009 — Parent exit**: Screen off, any device lock, manual exit, invalid state, or owning-process restart MUST immediately revoke PARENT_MODE. Unlock MUST never restore it; authorization mode is never persisted.
- **FR-010 — Child grant**: Offer 10 to 120 minutes in 10-minute steps, selected with up/down arrows and confirmed with Start. Starting MUST atomically establish one session with startedAt, softLandingStartsAt, and expiresAt, and clear parent-interface access. The selected duration includes Soft Landing. A brief parent-facing start confirmation MUST NOT retain access to dashboard, Settings, or grant actions; there is no separate “Hand over phone” step. After the confirmation the app moves to the background, so the child starts from the home screen (owner change 2026-10-19). Any later parent control requires fresh PIN entry.
- **FR-011 — Play-time accounting**: Locked time, sleep, background use, offline time, and repeated lock/unlock MUST count toward child duration except during a detectable active normal mobile/Telecom call. Third-party app calls (including WhatsApp), the generic audio-mode communication signal, undetectable calls, and ringing alone MUST NOT pause. Resume the same remainder when the detectable call ends; never reset to the selected duration. Overlapping active observations form one union pause. Permission loss, adapter failure, or unavailable observations MUST end that source’s pause eligibility. Starting a pause MUST retain the pre-pause next session boundary as a mandatory fresh-state checkpoint; cached ACTIVE or stale callbacks cannot renew it. If an end callback is lost, fresh INACTIVE/UNAVAILABLE or query failure at that checkpoint MUST close the pause and resume countdown. Lock/unlock MUST NOT reset, extend, or bypass a paused timer. Parent-facing remainder is nonnegative and accurate.
- **FR-012 — Boundaries**: A session initially grants exactly the selected 10–120 minutes (a multiple of 10) including Soft Landing, with startedAt < softLandingStartsAt < expiresAt. A call pause freezes its remaining play time and ramp progress; on resume, keep startedAt unchanged and shift softLandingStartsAt and expiresAt by the validated pause duration. Consequently expiresAt minus startedAt may exceed the selected play duration only by call-pause time. Validate against the current grant's elapsed-time accounting, not an assumption that wall duration always equals play duration. Outside a pause, before the effective softLandingStartsAt use CHILD_PLAY_SESSION; from that instant until effective expiry use SOFT_LANDING; at/after effective expiry use DULL. During a pause, stale deadline events cannot expire a session with positive frozen time. Reject unverifiable timing.
- **FR-013 — Clock safety**: Evaluate duration using trustworthy elapsed time including sleep, subtracting only validated call-pause time. Wall-clock changes MUST NOT increase remaining play time. Session timestamps exist only in memory and are never persisted; in-memory timestamps that fail validation, or uncertain timing continuity, MUST end authorization. Pause/resume accounting MUST be applied once per interruption, including repeated callbacks.
- **FR-014 — Ramp duration**: Default Soft Landing is two minutes; Settings offers one or two minutes. Changes affect the next session and MUST NOT move current deadlines.
- **FR-015 — Quiet transition**: Outside a validated call pause, Soft Landing MUST progress monotonically toward the captured dull profile and reach DULL at effective expiry. During a detectable active call, freeze remaining play/ramp time and retain the current ramp policy for non-excluded traffic. Resume from saved progress when detection ends, without replaying the ramp or granting more time. Expired/revoked grants never resume. No intentional network cutoff, child expiry UI, warning, sound, or vibration.
- **FR-016 — End/replacement**: Expiry clears current session timestamps with no history. A new PIN-authorized parent/child grant replaces the old grant; durations never accumulate. Manual Parent Mode exit needs no fresh PIN because it only reduces access.
- **FR-017 — Intensity**: Offer Gentle, Medium, Strong, with Medium default. DULL applies the selected network profile while shaping works and sets the maximum strength of enabled screen and sound effects (FR-034). Normal has no intentional throttle. Profiles MUST NOT visibly block apps or target content.
- **FR-018 — Generic VPN**: Use local VPN bandwidth and/or latency shaping without application-layer inspection, destination classification, or remote gateway/server. Apply generic policy to routed plaintext/encrypted traffic alike.
- **FR-019 — Capability truth**: Distinguish saved VPN preference/consent from actual functioning shaping. Incomplete setup, missing/revoked consent, conflicting VPN, failed startup, or known forwarding failure MUST cancel any current authorization and show the parent the network-dulling limitation with a calm remedy. SETUP_REQUIRED applies when no dulling method works (neither VPN dulling nor any enabled screen/sound effect with its access granted); then no grants are offered. While at least one method works, DULL is in effect and new grants may be offered, and the dashboard states exactly which methods are working. While DEACTIVATED, the VPN is intentionally stopped, so its absence is not SETUP_REQUIRED or a failure.
- **FR-020 — Recovery**: Permission approval, setup completion, or restarting shaping MUST NOT grant/resurrect normal use. Ordinary internet reconnection during a healthy valid child grant retains its deadlines; a service/process failure recovers to DULL.
- **FR-021 — Scoped V1 call handling**: Pause child play time only for normal system/mobile/Telecom calls where Android APIs and permissions allow; request READ_PHONE_STATE when required by the chosen API, with a parent-facing explanation first. WhatsApp and other third-party app calls MUST NOT pause play time in V1, and the generic audio-mode communication signal MUST NOT be used for pausing because it cannot identify the calling app. Do not claim universal or third-party call detection.

  VPN exclusions: exclude known system Phone/Dialer packages, the current default SMS/RCS app (resolved by package name through the public default-SMS API each time the VPN is established; no message or content access), and `com.whatsapp` using VpnService app exclusion where technically possible, so phone, messaging, and WhatsApp traffic is not slowed. Exclusions apply to the whole package without inspecting its content; they do not prove a call is active and never pause play time. Keep Dull Mode active for the rest of the device when authorization is DULL; retain the current Soft Landing stage during a pause. Calls never create a device-wide Normal override, grant Parent Mode, or revive old grants. A newly authenticated child grant during a detectable active mobile/Telecom call starts paused with its own selected duration.

  Privacy: do not collect call logs, phone numbers, call content/history/metadata, WhatsApp messages, notification content, contacts, phone numbers, or call logs. Do not inspect traffic content or collect/store network history, destination history, URLs, domains, payloads, or messages. Only minimal transient call state and current-session aggregate pause accounting are allowed.

  Permission fallback: missing, denied, revoked, or unavailable call detection means no pause and normal countdown. It does not block setup, sessions, VPN dulling, or implementation. Show only this parent-facing call limitation: “Play time pauses during normal phone calls. Calls in other apps, such as WhatsApp, are not slowed down but do not pause play time.”

- **FR-022 — Dashboard**: Behind PIN, show Active/Deactivated state, current mode, actual VPN status, dull intensity, setup status, and active session remainder. While DEACTIVATED, state plainly that Kids Garden is off and the phone is not dulled. SETUP_REQUIRED MUST explain that DULL authorization is selected while network dulling is limited/unavailable.
- **FR-023 — Settings**: Provide the Active/Deactivated switch (FR-031), screen and sound dulling controls (FR-036), PIN change, intensity, VPN setup/status and stop/re-enable controls, Soft Landing duration, limitations, and privacy explanation. VPN network dulling is one of four dulling methods the parent can enable or disable, alongside the three screen/sound effects (FR-036). Turning network dulling off stops the VPN and revokes any current grant; if another method is still enabled and working, DULL continues through it, otherwise SETUP_REQUIRED applies. The parent cannot disable all four methods: the last enabled method's switch explains that Deactivate (FR-031) is the way to turn Kids Garden off. This is distinct from Deactivate, which is a deliberate off state. Android's own VPN controls remain outside the app's PIN enforcement.
- **FR-024 — Change effects**: Intensity changes apply immediately in DULL. During a session, its captured profile and ramp duration remain until expiry; newly saved preferences apply afterward. A successful PIN change revokes existing phone and parent-interface authorization.
- **FR-025 — Storage**: Store only the allowlisted settings below, including the Active/Deactivated setting and screen/sound effect settings, using Jetpack DataStore with a JSON serializer. Authorization mode, child-session timestamps, pause accounting, and parent-interface access MUST be held in memory only and never written to storage. Persist settings across restarts, use schema versioning and safe updates, and exclude app data from cloud backup/device transfer where platform controls support it. No Room, SQLite, other database, or server.
- **FR-026 — Invalid data**: Missing/corrupt settings, unsupported schema, failed migration, or invalid in-memory session timing MUST yield DULL. A settings document containing unexpected authorization or session fields MUST be treated as invalid for those fields, which are ignored and dropped on the next write. Any document that fails validation is treated as unusable as a whole and replaced with safe defaults; the parent must enroll a new PIN and complete setup again. No partial salvage in V1. Report the current repair need privately without a failure log.
- **FR-027 — Restart rule**: On owning-process restart, update, or reboot, V1 MUST invalidate every grant. Because authorization and session timing are memory-only, a new process starts at DULL with no session when the saved setting is Active, or at DEACTIVATED with no session when it is Deactivated. This selects the safest allowed recovery behavior. Ordinary activity recreation or opening UI while the service stays healthy is not owning-process restart.
- **FR-028 — Honest limits**: Explain normal installation, uninstall/force-stop exposure, no full OS control or guaranteed app blocking, best-effort dulling and cached/offline limitations, no content/log collection, and future stronger Device Owner/DPC protection. Use the exact FR-021 parent-facing call limitation; never claim universal call detection.
- **FR-029 — Calm surfaces**: No child-facing “You are blocked,” “Your parent restricted YouTube,” “Time limit reached,” “Access denied,” or equivalent punishment/blame. Keep parent details behind PIN. App-controlled required notification wording MUST be neutral, e.g. “Kids Garden is running,” without session/mode details.
- **FR-030 — Local and free**: No backend, cloud account, remote dashboard/control, analytics, telemetry, ads, purchase, or subscription. The app MUST NOT contact remote services for its own purposes; forwarding existing device traffic to intended destinations is permitted.
- **FR-031 — Active/Deactivated switch**: The top of Parent options (behind PIN) MUST offer a switch between Active and Deactivated, default Active (owner change 2026-10-19: moved from Settings; switching needs no second PIN). While Deactivated, Parent options and the PIN entry screen MUST show plainly, in a warning colour, that Kids Garden is off and the phone is not dulled. Deactivating requires valid parent-interface access and the parent turning the switch off, which takes effect at once with no pop-up (owner change 2026-10-19). On commit, atomically: end any PARENT_MODE, child session, Soft Landing, or call pause with no history; stop VPN dulling and its foreground service; set authorization to DEACTIVATED; save Deactivated. While DEACTIVATED, the app MUST NOT enter DULL, SOFT_LANDING, PARENT_MODE, or CHILD_PLAY_SESSION, and lock, unlock, screen off, calls, reconnect, restart, update, and reboot leave it DEACTIVATED. Activating requires valid parent-interface access and turning the switch on, saves Active, starts VPN dulling, and moves to DULL; if dulling cannot start, DULL authorization with SETUP_REQUIRED applies. Activation never creates a normal-use grant. A failed save MUST leave the previous state in effect and tell the parent. Android’s own VPN controls and uninstall remain outside this switch.
- **FR-032 — Phone compatibility check**: After VPN consent in setup, and on demand from Settings and SETUP_REQUIRED screens, Kids Garden MUST run a short compatibility check that confirms the VPN starts, the foreground service is allowed, and test traffic actually passes through Kids Garden's local forwarding. The check MUST run entirely on the device and MUST NOT contact any remote service for Kids Garden's own purposes (FR-030), store results beyond the current setup flags, or create logs. The parent sees “This phone can be dulled” or one specific reason with a remedy: another VPN is active, a device or work policy blocks VPNs, VPN consent was not given, the foreground service is not allowed, or traffic did not pass through. Setup completion requires a passing check or at least one working screen/sound effect (FR-034). A phone whose VPN check fails is told plainly that the network cannot be dulled and offered the screen and sound effects instead; if no method works it is shown as unprotected under SETUP_REQUIRED and no grants are offered. The check never creates a grant. While DEACTIVATED (FR-031) the check is not run automatically.
- **FR-033 — Background-stop guidance**: When Kids Garden finds that its VPN is not running although the saved VPN preference is enabled and Kids Garden is Active (for example at the next process start after Android stopped it), the parent dashboard MUST say “Android stopped Kids Garden in the background” and offer optional battery-saver guidance. Guidance explains, in plain words, how to exclude Kids Garden from battery optimisation on that phone and opens the relevant Android settings page only when the parent taps it. Kids Garden MUST NOT request battery-optimisation exemption automatically, MUST NOT require it, and MUST NOT record how often it was stopped. Setup MAY show the same guidance as an optional step after a passing compatibility check.
- **FR-034 — Screen and sound dulling**: Kids Garden MUST offer three optional effects that apply only while authorization is DULL and Kids Garden is Active: (a) **Grey screen**: a see-through grey tint drawn over other apps that makes colours look washed out; it MUST let every touch pass through, stay at or below the opacity Android allows for pass-through touches, and never cover the status bar, system dialogs or the phone app during a call; (b) **Lower media volume**: caps media playback volume only, re-applying the cap if it is raised during the effect; it MUST NOT change call, ringtone, alarm, notification or accessibility volume; (c) **Lower brightness**: lowers screen brightness and pauses automatic brightness during the effect, eases back to the parent's saved level during each break, and restores the parent's previous brightness and automatic setting when the effects stop. Maximum strength follows the dull intensity: Gentle, Medium, Strong (initial targets: tint 40/60/75% opacity; media volume capped at 50/30/15% of maximum; brightness 60/40/20% of the starting level). Effects never block, lock, or hide apps, show text to the child, or inspect what is on screen. In PARENT_MODE, CHILD_PLAY_SESSION, SOFT_LANDING and DEACTIVATED all effects are off and brightness is restored. During a detectable active phone call all effects pause.
- **FR-035 — Gradual cycle**: Effects MUST NOT appear instantly. While DULL and the screen is on, each enabled effect runs a repeating cycle: **build-up**, rising smoothly from none to its maximum strength over the build-up time (default 2 minutes; Settings offers 1–5 minutes); then **break**, easing back to normal within about 10 seconds and staying normal for the break time (in the break the media volume cap is lifted but Kids Garden never raises volume itself, and brightness eases back to the parent's saved level) (default 1 minute; Settings offers 0–5 minutes, where 0 means the effect stays at maximum after build-up). Enabled effects share one cycle. When the screen turns off, the cycle stops; when it turns on in DULL, it starts again from the beginning of build-up. Entering DULL from a grant starts the cycle from none, so the change is never abrupt. The cycle MUST be event-driven and run only while the screen is on, with no wake locks or background loops.
- **FR-037 — Unlock grace** (owner change 2026-10-25, constitution v4.0.0): Settings (behind PIN) MUST offer an Unlock grace switch, on by default, with a grace time (1, 2, 3, 5, 10 or 15 minutes; default 5), a fade time (1–5 minutes; default 2) and a rest time (15 or 30 minutes, 1, 2, 3 or 4 hours; default 1 hour). While Kids Garden is Active, setup is complete, some dulling method works and authorization is DULL, an unlock without the PIN starts an in-memory grant: normal use for the grace time, then a gradual fade into DULL over the fade time using the Soft Landing ramp. Any unlock within the rest time after a grace started keeps the phone DULL; the first unlock after the rest time may start a new grace. Lock and unlock never pause or reset a running grace; a detectable phone call pauses it like play time. A parent grant replaces it. It never applies during Parent Mode, play time, Soft Landing or Deactivated, never shows the child any message, and never survives a restart as a grant. Only the grace start time is saved (overwritten each time) so a restart cannot reset the rest time; a wall clock set back before that time counts as still resting.
- **FR-036 — Effect permissions and capability**: Grey screen requires Android's “Display over other apps” access; lower brightness requires “Modify system settings” access; lower media volume needs no special access. Each access MUST be requested only after a parent-facing explanation and only when the parent enables that effect. A missing or revoked access disables only that effect and is shown on the parent dashboard; it never blocks setup, grants or VPN dulling. Settings (behind PIN) MUST provide an on/off switch for each dulling method: Network dulling (VPN, on by default), Grey screen, Lower media volume and Lower brightness (the three effects off by default), plus the build-up time and the break time, and MUST show which methods are actually working. At least one method must stay enabled (FR-023).

Safe defaults mean Active, DULL authorization, Medium intensity, two-minute Soft Landing, no child timestamps, and no parent-interface authorization. On fresh enrollment or a wholly unusable settings document, no credential is trusted, setup flags are incomplete, and the saved VPN-enabled preference is false. Existing Android VPN consent is checked independently; it cannot recreate a grant or justify claiming active dulling.

### State Model and Transition Contract

Authorization and actual shaping capability are distinct. DULL is an authorization decision, not proof that throttling is operating. SETUP_REQUIRED is a parent-only presentation over DULL when capability is absent. Known shaping failure cancels grants; ordinary loss of internet connectivity alone does not.

Detectable active calls pause child play-time accounting only; they are not a new authorization mode or VPN failure. Preserve remaining child time and Soft Landing progress; shift deadlines only by validated union pause time. Parent lock/screen-off and restart/invalid-state rules still apply. Missing call detection resumes countdown and shows the parent limitation without SETUP_REQUIRED or disabling working VPN dulling. Fixed package exclusions are independent of detection.

| Current state | Event/condition | Result |
| --- | --- | --- |
| DULL | Device unlock or accepted PIN without chosen action | DULL |
| Any state except DEACTIVATED | Valid parent-interface access + confirmed Deactivate, setting saved | DEACTIVATED; end any grant/pause; stop VPN dulling |
| DEACTIVATED | Lock, unlock, screen off, call, reconnect, restart, update, reboot | DEACTIVATED |
| DEACTIVATED | Fresh PIN + Activate, setting saved, dulling starts | DULL; new PIN/action required for normal use |
| DEACTIVATED | Fresh PIN + Activate, setting saved, dulling cannot start | DULL authorization; parent sees SETUP_REQUIRED |
| DULL | Detectable active call starts or ends | DULL remains for non-excluded traffic; no authorization or global network change |
| Any valid child grant | Call permission/observation becomes unavailable | Close unsupported pause eligibility once, continue countdown; parent-only limitation, no VPN failure |
| DULL | Fresh PIN + parent-use action, setup/capability valid | PARENT_MODE |
| DULL | Fresh PIN + child action + allowed duration, setup/capability valid | CHILD_PLAY_SESSION; immediately clear parent-interface access |
| PARENT_MODE | Screen off, lock, manual exit, invalid authorization | DULL; no retained parent grant |
| PARENT_MODE | Navigate to another app while on/unlocked | PARENT_MODE; parent-interface access closes separately |
| PARENT_MODE | Call starts/ends without lock, screen off, or other revocation | PARENT_MODE continues; no new PIN or confirmation |
| CHILD_PLAY_SESSION or SOFT_LANDING | Detectable active mobile/Telecom call begins with positive remaining time | Save remaining play/ramp time; retain current network policy and configured exclusions |
| Child authorization paused for calls | Last detectable active call ends or no active source remains available | Resume saved remainder; shift deadlines once by validated pause; no confirmation |
| CHILD_PLAY_SESSION | Lock/unlock before ramp boundary | Same session and deadlines |
| CHILD_PLAY_SESSION | Effective softLandingStartsAt reached before expiry outside a call pause | SOFT_LANDING |
| CHILD_PLAY_SESSION or SOFT_LANDING | Lock/unlock within effective ramp interval | SOFT_LANDING at elapsed play position; retain any active call pause |
| CHILD_PLAY_SESSION or SOFT_LANDING | Effective expiresAt reached/passed outside a call pause, even while locked | DULL; clear session |
| Any authorized state | Fresh PIN and explicit valid new grant | Replace previous grant |
| Any state | Invalid data/timing, owning-process restart, update, reboot | DULL if the valid saved setting is Active or settings are unusable (safe defaults); DEACTIVATED if the valid saved setting is Deactivated; setup status depends on remaining valid settings/capability |
| Any state | Incomplete setup, unavailable VPN, or shaping failure | DULL authorization; parent sees SETUP_REQUIRED |
| SETUP_REQUIRED | Setup and functioning VPN restored | DULL; new PIN/action required for normal use |

Resolve invalidity/expiry before stale UI/timer events. New grants require an unlocked, interactive phone and valid parent-interface access. A concurrent lock wins over starting Parent Mode. A child grant already committed before lock keeps its original duration.

### Non-Functional Requirements

- **NFR-001 — Platform/delivery**: Android phones running Android 15/16 only; free normal installed app. Start with a signed private APK/sideload, with internal testing as an alternative. No root, factory reset, DPC provisioning, or required custom launcher.
- **NFR-002 — Responsiveness**: While executing, parent actions/status updates MUST appear within one second in controlled tests. Revocation occurs at the logical lock/expiry event; the live engine applies it within one second of observing that event. After sleep, validate before forwarding newly handled traffic under stale unrestricted policy.
- **NFR-003 — Near-normal overhead**: Normal adds no intentional bandwidth cap/delay. In repeatable controlled tests, median throughput MUST reach at least 90% of VPN-off baseline and median additional round-trip delay MUST be at most 50 ms on reference devices. These bound Kids Garden overhead, not internet-provider performance.
- **NFR-004 — Efficiency**: No busy polling, wake loops, continuous screen monitoring, or periodic remote checks. Screen/sound effect cycles run only while the screen is on and stop when it turns off. A matched two-hour screen-off idle comparison MUST add no more than two battery percentage points versus VPN-off on reference devices; measure active-transfer cost separately for tuning.
- **NFR-005 — No logs**: No app-created logs in debug or release V1, including diagnostic, crash, PIN-attempt, network, and activity logs. No analytics/crash-reporting/tracking SDKs. External lab fixtures may record synthetic test results outside the installed product, without real child content.
- **NFR-006 — Accessibility**: Parent controls MUST have readable/screen-reader labels and usable focus order; system font scaling MUST not obscure PIN/actions. App-controlled notifications MUST expose no PIN or child session/mode details on the lockscreen.
- **NFR-007 — Verification**: Deterministically verify every state transition, boundary, default, corruption recovery, and schema migration. Device tests MUST cover both OS versions, sleep/lock, lifecycle, VPN, reboot, essential calling, and UI accessibility. This document does not assert implementation completion.
- **NFR-008 — Language**: V1 user-visible text (parent screens, errors, limitation text, notifications) is English only. All such text MUST come from Android string resources, with no hard-coded user-visible strings in code, so later localization requires only translation.

### Key Entities *(include if feature involves data)*

| Entity | Allowed data and lifecycle |
| --- | --- |
| Settings document | schemaVersion, Active/Deactivated setting, screen/sound effect switches with build-up and break times, the parent's brightness level and automatic setting to restore after the brightness effect, PIN credential data, dull intensity, VPN enabled preference, VPN dulling configuration, Soft Landing duration, setup completion flags. No authorization mode or session data. No historical versions retained by the product. |
| PIN credential | Salt, hardened verification value, required parameters/version; never raw or reversible PIN, never exported. |
| Authorization | Current DULL/PARENT_MODE/CHILD_PLAY_SESSION/SOFT_LANDING/DEACTIVATED value, held in memory only; every process start begins at DULL, or DEACTIVATED when the saved setting is Deactivated. SETUP_REQUIRED is derived parent status. |
| Current child session | Held entirely in memory, never persisted: startedAt, expiresAt, softLandingStartsAt for one session (deadlines shift on call resume), elapsed anchors, selected play duration, remaining play/ramp time during a pause, aggregate pause accounting, and current-session profile snapshot. Discarded on expiry/replacement/invalidation and lost on process restart. No list of past calls or interruptions. |
| Dulling configuration | Normal and Gentle/Medium/Strong bandwidth/latency settings; fixed Phone/Dialer, default SMS/RCS app, and com.whatsapp VPN exclusions only (the SMS package is resolved at VPN setup, not persisted), no parent-managed app rules or destination rules. |
| Setup/capability | Persist setup flags and requested VPN-enabled preference only (part of the settings document). Actual consent, active service, current error/limited status are checked/derived in memory, never a history. |

### Privacy Constraints

Allowed persistence is an exhaustive allowlist. No logs, history, usage history, URLs, visited domains, DNS query names, messages, notifications, contacts, screenshots, keystrokes, search queries, video titles, app content, location, child behavior records, or analytics events may be collected, stored, or transmitted by Kids Garden.

Traffic handling may transiently process minimum packet headers and opaque bytes needed for forwarding/shaping. This does not permit decoding payloads, parsing DNS names, classifying destinations, packet capture retention, TLS interception, or an inspection certificate. Bound and discard temporary forwarding buffers/connection state when no longer needed; never turn destination/app counters into records or reports.

Exclude settings/credentials from Android cloud backup and device transfer where supported, verify platform-specific configuration, and never restore authorization from transferred data. Android itself may create system diagnostics/UI outside app control; Kids Garden promises no app-created logs, not suppression of OS records. Android documents separate backup/transfer behavior requiring explicit configuration. [Android Auto Backup](https://developer.android.com/identity/data/autobackup)

### Android Feasibility Constraints and Planning Gates

These capture user/project constraints and required feasibility work, rather than prescribe an implementation design.

- **AFC-001 — Foundation**: Use Android VpnService for local traffic handling and Jetpack DataStore with a JSON serializer. IDE: Android Studio; implementation agents: Codex CLI and Claude Code. Carry compatible constitution requirements for Kotlin, Compose, Gradle, and a centralized testable state machine into planning. No backend may be introduced to solve forwarding.
- **AFC-002 — VPN scope**: Android requires VPN consent and permits one active VPN per user/profile. Revocation or switching VPNs can remove Kids Garden's traffic path; explain this limitation. [Android VPN guide](https://developer.android.com/develop/connectivity/vpn)
- **AFC-003 — System visibility**: Android exposes VPN indicators and service notifications. No-confrontation means neutral app wording, not hiding required disclosure. Validate foreground-service types, permissions, and launch rules for both OS versions. [Android VPN guide](https://developer.android.com/develop/connectivity/vpn), [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- **AFC-004 — Local forwarding**: VpnService provides a packet interface, not a complete shaping engine. Planning MUST prove on-device forwarding/shaping without remote gateways, inspection, or intentionally breaking unsupported traffic. Include IPv4/IPv6, TCP/UDP/QUIC, DNS transport, reconnect, and resource tests. This engineering gate is inferred from the packet-interface model. [VpnService reference](https://developer.android.com/reference/android/net/VpnService)
- **AFC-005 — Lifecycle honesty**: A normal app cannot guarantee dulling after uninstall, force-stop, VPN removal, or unavailable execution. Logical DULL recovery does not guarantee continuous throttling. Always-on is optional platform setup, not tamper protection; do not require/enable “block connections without VPN” as a dulling substitute. [Android VPN guide](https://developer.android.com/develop/connectivity/vpn)
- **AFC-006 — Time**: Android distinguishes adjustable wall time from elapsed time including deep sleep. Choose compliant scheduling; delayed callbacks must not extend grants. Exact alarms or battery special access are not assumed or automatically requested; battery-optimisation guidance (FR-033) is parent-initiated and optional. [SystemClock reference](https://developer.android.com/reference/android/os/SystemClock)
- **AFC-007 — Scoped call qualification**: Implement and qualify normal mobile/Telecom detection where public APIs allow. WhatsApp call detection is not implemented in V1. Verify Phone/Dialer and com.whatsapp VPN exclusions where possible, pause/resume, lock/unlock, permission denial/loss, unsupported-call countdown, and expiry. Record device limitations without content inspection or logs. Universal detection is not a V1 implementation or release gate; unsupported calls do not prevent Phase 1/2 or otherwise qualified dulling. Retain scoped acceptance tests and unrelated VPN/lifecycle/privacy/quality checks. Never place live emergency calls for testing.
- **AFC-008 — Effect limits**: Only traffic actually routed through working shaping can be dulled. Cached/offline content, other profiles, OEM behavior, and app adaptation limit results. Qualify every advertised configuration and disclose exceptions; never promise selective YouTube control or boredom in every app.

### Initial Network Profile Targets

Calibration starting points may change through implementation/testing with documented rationale in project artifacts, without product logs. Rates below are aggregate per-direction caps across shaped traffic, so parallel connections do not multiply allowance. Latency is additional round-trip delay; do not add the full amount in both directions. Essential-use safety takes priority.

| Profile | Initial bandwidth | Initial added latency | Intent |
| --- | --- | --- | --- |
| Normal | No intentional cap | No intentional delay | Parent use and early child session |
| Gentle | About 256 kbps | About 300 ms | Mild slowdown |
| Medium (default) | About 128 kbps | About 600 ms | Noticeable slowdown |
| Strong | About 64 kbps | About 800–1200 ms | Very slow network-heavy entertainment |

Soft Landing uses the current session's captured profile and duration. Parent-authorized profile changes during play apply after that session expires.

### Acceptance Coverage

All requirements are release obligations, not optional backlog. This mapping connects requirement groups to review evidence without embedding a checklist.

| Requirements | Acceptance evidence |
| --- | --- |
| FR-001–003 | Story 1 and all unauthorized unlock/setup transitions |
| FR-004–007 | Stories 2/3/5; one-to-six-digit validation, masking, confirmation, retry delay, stale actions, immediate parent-interface lock at child start, notification/deep-link entry |
| FR-008–009 | Story 2; screen off, manual/automatic lock, app navigation, manual exit, restart |
| FR-010–016 | Stories 3/4; every duration, both ramp durations, exact boundaries, call pause/resume and overlaps, clock changes, replacement, locked expiry, immediate parent-interface lock |
| FR-017–020 | Stories 1/4/5; controlled profiles, capability failures, no inspection, reconnect |
| FR-021 | Story 6 plus scoped qualification AFC-007 |
| FR-022–024 | Story 5; complete dashboard/settings inventory, persistence, current/next-session changes |
| FR-025–027 | Story 6; schema/migration/corruption/write-failure tests and restart/reboot matrix |
| FR-028–030 | Stories 1/5/6; copy, dependency, storage, output, and outbound-traffic review |
| FR-034–036 | Story 5 (#9–11); each effect at each intensity, cycle timings, screen-off/grant/call interruption, access denied/revoked, brightness restore after process death, touch pass-through, no change to call/alarm/ring volume, SC-010 |
| FR-032–033 | Story 1 (#6–7); compatibility pass and each failure reason on API 35/36, local-only check with no outbound app traffic, background-stop detection and optional guidance |
| FR-031 | Story 5; deactivate from every mode, persistence across restart/reboot, activate with and without working VPN, save failure |
| NFR-001–008, AFC-001–008 | Android 15/16 qualification, accessibility, battery/performance comparisons, deterministic tests, privacy review, resolved feasibility gates |

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001 — Setup**: At least four of five representative parents complete PIN/VPN setup on a supported configured device within five minutes without assistance; all five locate limitations/privacy. Use adult participants and synthetic sessions, not child tracking.
- **SC-002 — Default safety**: While Active, in 100% of defined unauthorized unlock, invalid-state, restart, and reboot cases, no normal-use authorization is granted. Only an authenticated, confirmed Deactivate leaves the phone undulled. Every unavailable-dulling case is accurately identified to the authenticated parent.
- **SC-003 — Parent handoff**: Across 20 lock/screen-off cycles per OS version, zero Parent Mode grants survive. Fresh valid parent use starts within one second while the app is operating. In every child-session-start test, parent controls are immediately PIN-protected; zero Back, cached-screen, notification, or link paths expose them without fresh authentication.
- **SC-004 — Preserved play duration**: Test 10, 60, and 120 minutes with at least 10 lock/unlock cycles per OS version, both with and without calls. Lock, sleep, time change, and reconnect never themselves pause or increase play time. Detectable active mobile/Telecom calls freeze the remaining play/ramp time; WhatsApp and other app calls do not; effective deadlines move later by exactly the union of validated pause intervals, within NFR-002's observation bound. Verify the 30-minute grant, 5-minute use, 25-minute call example leaves exactly 25 minutes of play within that bound. Repeated/overlapping calls cannot double-credit time; expired/revoked grants never resume. A lost call-end callback is recovered at an event-driven or mandatory boundary revalidation; cached ACTIVE state never creates an indefinite pause or unlimited play.
- **SC-005 — Quiet end**: Every uninterrupted one/two-minute ramp test passes through at least three increasing dulling levels, reaches DULL at effective expiry, and shows zero app-created child warnings, blocking screens, or expiry alerts. With a detectable active mobile/Telecom call interruption, ramp progress freezes at the current policy and the saved remainder resumes afterward without a new full ramp, confirmation, or alert.
- **SC-006 — Useful differentiation**: Across five matched controlled transfers per profile/device, Gentle, Medium, Strong show progressively lower median throughput and/or greater delay within 20% of configured targets; Normal meets NFR-003. At least four of five adult evaluators distinguish Medium from Normal using identical uncached network-heavy activity without mode labels.
- **SC-007 — Privacy**: Storage, dependencies, output, and outbound-connection review finds zero prohibited fields, app-created logs, raw PINs, analytics events, or app-originated remote-service connections across setup/use/failure paths.
- **SC-008 — Scoped call handling**: On Android 15/16 reference devices, verify mobile/Telecom pause/resume where allowed, and Phone/Dialer, default SMS/RCS app, and com.whatsapp VPN exclusions where possible while other traffic stays dull. Verify WhatsApp calls are not slowed and do not pause play time, game voice chat does not pause play time, and denied/unavailable permission continues countdown, with exact parent-only limitation text. No universal detection claim or universal-call feasibility prerequisite remains.
- **SC-009 — Quality**: All functional acceptance cases pass on Android 15/16 reference devices, parent screens pass accessibility checks, and idle battery meets NFR-004. No release is ready while a feasibility gate is unresolved.
- **SC-010 — Screen and sound dulling**: On Android 15/16 reference devices, with each effect enabled alone and all together: effects start from none and reach full strength within the build-up time ±5 seconds, ease back within about 10 seconds, and repeat; they stop within one second on a grant, an active phone call, screen off or Deactivate; every touch passes through the grey screen; call, ringtone, alarm, notification and accessibility volume never change; the parent's brightness and automatic setting are restored after the effects stop and after process death; and battery use with the screen off is unchanged from effects off (NFR-004).

## Assumptions

- One parent credential and one authorization per installation in the active Android user/profile. No multi-parent accounts, child profiles, or multi-device management.
- The parent handles installation/setup; the child may know device unlock credentials. Kids Garden authentication is independent.
- Defaults chosen for unspecified details: one-to-six-digit PIN (changed 2026-10-12); 30-second volatile retry delay after five failures; Medium intensity; two-minute Soft Landing with one/two-minute choices; parent-interface reauthentication after leaving it. Retry delay does not survive process death and is not strong anti-tamper protection.
- Deactivated is a deliberate parent choice, not a grant: it has no timer and persists until the parent activates again. Anyone who knows the PIN can deactivate, so PIN secrecy matters as much as for other parent actions.
- Safest permitted restart policy is selected: all grants end on owning-process restart/update/reboot, whereas healthy activity recreation does not itself end a child session or unlocked Parent Mode.
- Authenticated settings remain accessible during limited setup, but new normal-use grants require working setup/shaping. Missing VPN may leave networking normal despite DULL authorization; this must be disclosed.
- External controlled measurements do not introduce a product backend or stored usage measurements. Android/other-app notifications are outside Kids Garden's copy control.
- The explicit V1 scope correction supersedes earlier universal-call requirements. Normal mobile call pause and WhatsApp VPN exclusion are V1; WhatsApp and other app calls continue countdown. Local forwarding, lifecycle, privacy, and resource qualification still apply without a universal-call prerequisite.
- Only detectable active mobile/Telecom calls pause child play and Soft Landing, including while locked. Ringing alone and lock/unlock never create pauses. End/loss of valid detection resumes the same remainder; overlapping observations cannot double-credit time. Parent revocation and grant invalidation remain authoritative.

## V1 Non-Goals

- True uninstall prevention, strong tamper protection, Device Owner/DPC provisioning, factory reset, root, full OS control, mandatory custom launcher, approved-app-only access, or guaranteed total app blocking.
- Cloud dashboards/accounts, remote control/services, sync, databases, ads, monetization, analytics, logs/history, or child activity/usage reports.
- Bedtime/school schedules, automatic sessions, category rules, parent-managed communication-app allowlist, stronger app blocking.
- AI screen analysis, camera/microphone detection, face recognition, screenshots, message/notification reading, browsing/search/video-title collection, app content collection, location or surveillance.
- Usage Access/UsageStatsManager is not required/requested by core V1. A separately specified V1+ may consider package-level foreground awareness only, with no content collection or stored activity history.
- Guaranteed dulling of cached/offline content, iOS/desktop support, other profiles, or cross-device control.
- Languages other than English, and right-to-left layouts.
- True system greyscale (the V1 grey screen is a see-through tint), per-app volume or brightness rules, and dimming of the status bar or system dialogs.

## V2 Backlog

Call-related work deferred to V2 only: play-time pause for WhatsApp, Telegram, Discord, Messenger, and other third-party communication apps (requires a privacy-compatible way to identify the calling app; in-game voice chat must never pause play); parent-managed communication-app allowlist; stronger emergency/call behavior under Device Owner/DPC mode. Normal mobile-call pause and WhatsApp VPN exclusion remain V1.

Device Owner/DPC mode; stronger uninstall/tamper resistance; better parent-owned device provisioning; optional custom launcher; approved apps only; bedtime/school schedules; parent-managed communication-app allowlist; remote parent controls; optional app-category rules; stronger blocking. Remote controls require an explicit future privacy/architecture decision and do not authorize V1 infrastructure.

## Constitution Alignment

The [constitution](../../.specify/memory/constitution.md), version 3.1.1, aligns with this V1 authorization model. Principle I permits enjoyable use only through explicit PIN-plus-action authorization: untimed PARENT_MODE for the parent, ending on lock/screen-off/exit/invalid state/restart, or a timed CHILD_PLAY_SESSION for the child. Principle III prohibits V1 app-created logs in debug and release. Scoped detectable-call pauses, lock/unlock timer invariance, Dull-by-default behavior, privacy, local-only storage, Android compliance, and deterministic state-machine testing remain mandatory while Kids Garden is Active. The parent Active/Deactivated switch (FR-031, added 2026-10-15) lets an authenticated parent turn dulling off indefinitely, which the v3.0.0 amendment (2026-10-15) explicitly permits. Screen and sound dulling (FR-034–036) is permitted by the v3.1.1 amendment (2026-10-18). No constitutional exception is required.


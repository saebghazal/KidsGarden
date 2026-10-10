# Contract: CallStateMonitor

**Scope**: V1 pauses child play only for normal mobile/Telecom calls (cellular, IMS, system Wi-Fi calling) observed through per-subscription telephony call state. WhatsApp and other third-party app calls never pause play time. See [research R1](../research.md#r1--scoped-v1-call-handling-telephony-only).

## Public boundary

`observations: StateFlow<CallObservation>` exposes only the aggregated telephony source state ACTIVE / INACTIVE / UNAVAILABLE, the current monitor generation and elapsed observation time. `start()`, `reconcile()` and `stop()` manage public Android subscriptions and current-state validation. Events contain no numbers, contacts, SIM or subscription identifiers, call identity, notification content or call metadata/history. Only transient minimal state and aggregate current-session pause duration are allowed.

The reducer treats ACTIVE as one union pause. Keep call capability separate from VPN readiness and setup eligibility. INACTIVE never claims absence of every possible app call. Test sources are injectable and absent from production wiring.

## Prohibited signals

The monitor MUST NOT use `TelecomManager.isInCall()`, the deprecated device-wide `TelephonyManager.getCallState()`, `AudioManager` mode or mode listeners, notification listeners, Accessibility, Usage Access, foreground-app heuristics, microphone state, traffic analysis, hidden APIs, privileged roles or polling. These either include third-party calls (letting game voice chat freeze the timer) or are prohibited by the spec.

## Observation validity and recovery

An ACTIVE observation is valid only while all of these conditions hold:

- its monitor generation is current;
- its callback registration/subscription continuity has not been lost;
- READ_PHONE_STATE and the public API remain available; and
- the source has not emitted INACTIVE/UNAVAILABLE or failed a mandatory fresh-state query.

Starting a pause MUST retain the session's already scheduled next play/ramp/expiry boundary as a
mandatory one-shot revalidation checkpoint; pausing MUST NOT cancel that fallback. Lifecycle,
permission, subscription-change and monitor-restart events trigger earlier event-driven
reconciliation when they occur. At a checkpoint the adapter MUST query current per-subscription
state; cached ACTIVE state or a stale callback MUST NOT renew validity. A fresh INACTIVE or
UNAVAILABLE result closes the union pause once and resumes countdown. A fresh ACTIVE result may
establish the next one-shot checkpoint at the next projected session boundary. This is boundary
revalidation, not a polling loop.

If a call-end callback is lost, the next event-driven reconciliation or mandatory boundary
checkpoint therefore closes the pause unless the source can freshly confirm an active phone call.
If the query fails, permission is lost, registration continuity is uncertain, or the adapter
cannot prove current ACTIVE state, map the source to UNAVAILABLE and continue countdown. No cached
observation may freeze time indefinitely, and revalidation may never create a new grant or reset
remaining play time.

## Android adapter

- Register a `TelephonyCallback` implementing `CallStateListener` on a `TelephonyManager` created for each active subscription (`createForSubscriptionId`). Re-register on subscription changes. Aggregate: ACTIVE if any subscription reports OFFHOOK; INACTIVE if all report IDLE or RINGING.
- Map OFFHOOK to ACTIVE (it may include dialing/held calls; do not claim connected-media precision). RINGING alone is INACTIVE.
- Request READ_PHONE_STATE only after the parent-facing explanation screen. Denial or later revocation maps to UNAVAILABLE.
- Handle initial state, duplicates, idle and permission loss. Never carry cached ACTIVE across monitor restart or use stale-generation callbacks to restore a pause.
- Device qualification must show a WhatsApp call and a game voice chat do not produce OFFHOOK on any subscription. Where they do, report the source UNSUPPORTED on that device and continue countdown.

## Accounting and VPN separation

Pause only a valid unexpired child grant while the telephony source is validly ACTIVE. When it becomes INACTIVE/UNAVAILABLE, close the union interval once and resume the remaining play/ramp time. Lock/unlock neither cancels nor resets that pause. Permission denial or loss creates no free time, new grant, SETUP_REQUIRED state, or VPN bypass. Restart/invalid timing follows existing grant revocation rules. Expiry after pauses still follows Soft Landing then DULL.

Communication-app VPN exclusions (dialer, default SMS/RCS app, `com.whatsapp`) are owned by [VpnDullingController](vpn-dulling-controller.md). They are independent from this monitor, never pause play time, and package presence is never evidence of an active call.

## Parent limitation and verification

When permission/detection is missing or unavailable, show only this parent-facing call limitation:

> Play time pauses during normal phone calls. Calls in other apps, such as WhatsApp, are not slowed down but do not pause play time.

Unit tests cover mobile pause/end, multiple subscriptions forming one pause, ringing-only, initial/duplicate observations, permission denial/revocation, source loss, stale generations, lock/unlock, exact expiry, paused ramp progress, and that no third-party call input exists in the API. A dedicated lost-end-callback test proves cached ACTIVE cannot renew at the mandatory boundary and that INACTIVE/UNAVAILABLE fallback resumes countdown without resetting remaining time. Device tests separately establish actual telephony behavior and that WhatsApp/game voice calls do not pause.

References: [Telephony callback](https://developer.android.com/reference/android/telephony/TelephonyCallback.CallStateListener), [Telephony call-state definitions](https://developer.android.com/reference/android/telephony/TelephonyManager#CALL_STATE_OFFHOOK), [createForSubscriptionId](https://developer.android.com/reference/android/telephony/TelephonyManager#createForSubscriptionId(int)).

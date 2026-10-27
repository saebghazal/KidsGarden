# Contract: VpnDullingController

**Layer**: policy effect boundary between SessionManager, permission/service adapter and conditional forwarding engine.

## Public surface

| Operation | Input → output | Contract |
| --- | --- | --- |
| capability(): StateFlow<VpnCapabilitySnapshot> | none → live coarse capability | Consent, service and actual forwarding health/error status; fixed exclusion availability. Call detection is separate and cannot make a healthy VPN unavailable. Saved preference is not proof. |
| requestPermission(activity) | visible parent Activity → consent result | Explain local generic shaping and privacy before Android consent. Cancellation yields limited status and no phone grant. |
| start(policy, generation) | authorization policy + generation → suspend Result<AppliedPolicy> | Configure fixed communication-package exclusions where possible before establishing TUN. Start through an eligible foreground-service flow; publish RUNNING only after actual forwarding is healthy. No universal-call prerequisite. |
| apply(policy, generation) | NORMAL, DULL, or SoftLanding + generation → Result<AppliedPolicy> | Serialize per generation; ignore stale policies. Authorized Normal clears artificial delay without resetting flows. During a call pause retain the current policy; Soft Landing freezes at its current stage. |
| configureCommunicationExclusions() | none → per-package applied/skipped result | Resolves default/system dialer, default SMS/RCS app and com.whatsapp at each establish (results not persisted). Uses VpnService.Builder.addDisallowedApplication where technically possible, relying on manifest `<queries>` for visibility; no global bypass. The parent's list of apps that skip Kids Garden (FR-038) is added to the same exclusions at every establish; Kids Garden itself is never excluded. Missing/unsupported packages cannot block other exclusions or otherwise functioning dulling. |
| reconfigure() | none → Unit | The bypass list changed: while RUNNING, ask the service to `establish()` a replacement interface with the new exclusions, restart forwarding on it and close the old one, without reporting lost capability (grants continue). Not running: no effect; the next start uses the list. Never starts a stopped or Deactivated VPN. |
| checkCompatibility() | none → suspend CompatibilityResult | Local only: consent, VPN-blocking device policy, another active VPN, foreground-service eligibility, then a forwarding self-test through the TUN with no remote destination. Returns Pass or one reason; no persistence beyond setup flags, no logs, no grant. |
| stop(reason) | coarse reason (including DEACTIVATED) → idempotent result | Clear queues/flow state, close TUN/relay/protected sockets, report live status. Stop/revoke fails open with ordinary routing, not intentional network blackholing. No diagnostic output. |
| onRevoke/onUnderlyingNetworkChanged | platform event → event/result | Close/replace invalid resources, tell SessionManager to cancel grant if capability is lost; network reconnection alone does not renew a timer. |

## Policy type

`ThrottlePolicy.Normal`; `Dull(intensity, configuration)`; `SoftLanding(targetIntensity, rampFraction, configuration)`; `Unavailable(coarseReason)`. There is no SuspendedForCall network policy.

Precedence: DEACTIVATED → VPN stopped by `stop(DEACTIVATED)`, reported as intentionally off (not an error or limited protection); unavailable VPN/forwarding → unshaped limited VPN status; excluded packages bypass the VPN; all remaining traffic follows Parent Mode/early child → Normal, Soft Landing → current staged ramp, DULL → selected Dull profile. Call detection only affects session accounting; missing call permission never disables shaping. SETUP_REQUIRED is not authorization.

Normal has no intentional cap or delay. Dull aggregate cap per direction (decimal kbps): Gentle 256/300 ms added RTT; Medium 128/600; Strong 64/1000 initial. RTT is split between directions. Soft Landing interpolates target-relative stages at one-third, two-thirds and final; Gentle never passes through Medium.

## Operational and privacy contract

Candidate engine uses Android VpnService + audited user-space forwarding stack + authenticated loopback-only relay + protected Wi-Fi/cellular egress sockets. There is no remote gateway. Start only from supported system/parent lifecycle. Use the VPN-eligible `systemExempted` foreground-service type only when eligible; do not use DPC, exact alarms, `dataSync`, default-dialer role, hidden APIs, or an activity launched from the background as a workaround.

Forward opaque routed bytes, inspect only headers necessary for forwarding. No DNS-name mapping, URL/SNI/content parsing, traffic destination display/history, packet dumps, statistics export, TLS interception, per-app shaping rules (a bypass list only excludes whole apps), logs or native stdout/stderr printing. Bound memory/active flows; clear volatile buffers when closed. Each source/submodule/license must be pinned/audited before selection. Candidate dependency is not yet approved.

## Tests and release condition

Fake-engine tests verify each compatibility outcome and that the check opens no outbound socket, plus mode policies, stop on Deactivate and no start while Deactivated, frozen ramp policy, stale generations, separate call/VPN capability, dialer/SMS/com.whatsapp exclusions, missing packages, unresolvable defaults and exclusion failures. Synthetic network tests qualify forwarding, privacy, profiles, reconnect, battery and native packaging. Physical API 35/36 tests verify excluded packages bypass shaping while non-excluded traffic remains dull. Universal-call detection is not required; scoped acceptance and G-VPN/G-LIFECYCLE/G-PRIVACY/G-QUALITY still apply.

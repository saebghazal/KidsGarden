# Contract: Android Events, VPN, and Capability

**Status**: Scoped V1 platform contract. Implementation can proceed; Android device qualification remains required without a universal-call prerequisite.

## Adapter ownership

Android components translate platform observations into domain events. They do not choose authorization, restart child timers, or persist any mode or session data.

| Adapter | Input / output | Failure behavior |
| --- | --- | --- |
| PermissionStatusProvider | Live VPN preparation/notification/eligible service/optional phone permission observations; explanation screen precedes each system prompt | Unknown or revoked capability is unavailable, not saved=true |
| VpnPermissionLauncher | Parent explanation → official VpnService.prepare consent result | Denial/cancel stays limited; success creates no grant |
| DeviceEventCoordinator | UI resume, screen, user-present, boot/update, clock and network observations → reducer events | Unclear authorization is DULL; no polling workaround |
| CallStateMonitor | Per-subscription telephony active/inactive/unavailable observation → reducer (no third-party call input) | Missing permission, unsupported API or lost observation removes pause eligibility; countdown and working dulling continue |
| KidsGardenVpnService | Lifecycle/TUN/foreground ownership | Close resources, invalidate grants, expose parent current status |
| VpnDullingController | Domain network policy + generation → engine acknowledgment | Stale acknowledgment ignored; unsafe capability disables shaping |
| ForwardingEngine | Protected local forwarding and policy application | Return typed coarse failure without printing or retaining traffic |

## Permission and component contract

| Permission / component | Intended use |
| --- | --- |
| INTERNET | Forward existing device traffic through protected egress sockets; no app telemetry/backend |
| ACCESS_NETWORK_STATE | React to network handover and connection status |
| FOREGROUND_SERVICE and FOREGROUND_SERVICE_SYSTEM_EXEMPTED | Configured VPN foreground operation only when platform eligibility holds |
| POST_NOTIFICATIONS | Parent explanation screen first, then request for neutral foreground notification; respect denial and platform-required visibility |
| RECEIVE_BOOT_COMPLETED | Reconcile DULL on permitted post-boot execution; no pre-unlock protection claim |
| BIND_VPN_SERVICE | Service declaration restricted to Android; not a runtime parent permission |
| READ_PHONE_STATE | Parent explanation screen first, then runtime request for per-subscription telephony call state. Denial/revocation continues countdown and shows only the parent call limitation. |
| `<queries>` | `<package android:name="com.whatsapp"/>` plus intent queries for `ACTION_DIAL` and `ACTION_SENDTO` with `smsto:` so exclusion packages are visible; no QUERY_ALL_PACKAGES |
| Activity launch | Launcher exported as needed; no externally trusted grant extras |
| Boot/update receiver | Manifest system-action receiver with minimal protected filters and bounded work |
| Screen/user-present receiver | Runtime registration for protected system actions; registration flags chosen/tested for actual system sender |

No READ_CALL_LOG, contacts, SMS read/send, QUERY_ALL_PACKAGES, notification listener, microphone/camera, accessibility, Usage Access, overlay, default dialer/assistant role, Device Owner, hidden API, exact-alarm privilege, or broad package inventory is authorized by this plan. A future permission need must be justified against the existing privacy boundary, not silently introduced.

Do not declare a manifest-only ScreenStateReceiver and assume SCREEN_OFF will arrive. Sample isInteractive, display state and isDeviceLocked/isKeyguardLocked at relevant permitted lifecycle points. Public snapshot APIs do not establish universal instantaneous keyguard events; G-LOCK remains a qualification obligation. User-present always removes a surviving Parent Mode grant.

## Scoped call and exclusion contract

Use [CallStateMonitor](call-state-monitor.md) without content/notification inspection. Before each `establish()`, resolve the default dialer (`TelecomManager.getDefaultDialerPackage()`), the system dialer (`getSystemDialerPackage()`), the default SMS/RCS app (`Telephony.Sms.getDefaultSmsPackage`) and `com.whatsapp`, and exclude each with `VpnService.Builder.addDisallowedApplication`. Exclusions cover the entire package, including non-call WhatsApp and messaging-app traffic; do not classify messages or media. Exclusions never pause play time. Resolution results are not persisted; a changed default SMS app takes effect at the next establish. Do not mix VPN allowed-app and disallowed-app lists. `NameNotFoundException` or an unresolvable default is handled per package (skip that entry) without globally disabling dulling. Resolve only these defaults and the fixed package; no broad app inventory, contacts, or usage history. Rebuild through normal lifecycle if exclusion configuration changes.

[Android VPN app exclusion reference](https://developer.android.com/reference/android/net/VpnService.Builder#addDisallowedApplication(java.lang.String)).

## Lifecycle

1. Visible parent setup explains local forwarding/privacy/limits, obtains VPN consent, and reports real capability.
2. Start eligible foreground service with notification before startup deadlines. Consent may have changed since previous checks; establish may return null.
3. Prepare safe engine resources before capturing traffic. If forwarding cannot be made operational, close/release rather than blackhole networking.
4. Apply shaping when VPN/forwarding capability is qualified. Configure dialer, default SMS/RCS and com.whatsapp exclusions where possible; call detection availability is not a VPN startup or shaping prerequisite.
5. On consent revoke, second VPN, user stop, service/process failure: invalidate authorization; close TUN/relay sockets/buffers; stop the foreground service where required. Avoid restart loops.
6. On boot/update or owning-process recreation: clear all grants, then recover only through allowed platform entry points. Never grant Normal based on disk.
7. Initial supported setup does not depend on always-on VPN or lockdown. Explicitly opt out of unsupported always-on behavior.
8. Normal/child/call/ramp policy changes should update an existing healthy engine without tearing down flows. No network reset per stage.

No background activity launch for expiry, no child modal, no alarm sound. Required notification text is neutral: “Kids Garden is running.” Taps route through PIN; no notification action grants, extends play, or exposes parent Settings.

## Forwarding-engine contract

`start(configuration, protectSocket)` accepts a validated numeric/local configuration and returns Running only after actual forwarding is operational. `apply(policy, generation)` acknowledges the effective generation. `stop()` is idempotent and destroys transient data. Errors contain an allowlisted reason code, not payloads, IPs, URLs or exception dumps.

Engineering candidate: TUN → audited userspace stack → authenticated loopback-only SOCKS relay → protected TCP/UDP sockets → existing Wi-Fi/cellular network. No remote gateway, remote DNS proxy introduced by the product, or LAN-facing proxy. Local relay requests use numeric IP addresses; no hostname lookup/classification in that protocol. Resolver traffic is forwarded as opaque traffic, not decoded into query names.

Before every egress socket sends traffic, successfully call VpnService.protect (including native sockets via audited bridge) and handle underlying-network binding. Failure must not produce VPN loops. Respect MTU, transport semantics, IPv4/IPv6, UDP/QUIC and system resolver operation; protocol gaps are failures, not intentional app blocking.

Use bounded fair per-direction scheduling, monotonic release times and aggregate rate budgets. Proposed initial safety budgets for prototype measurement: 16 MiB total queued bytes and 1024 active flows; tune only with recorded lab evidence. Apply backpressure to TCP and normal bounded best-effort datagram overflow handling, never unbounded allocation or content-specific drops. Do not present a protocol-breaking prototype as a validated dulling profile.

Packets remain opaque beyond required IP/transport metadata. Never parse HTTP paths, SNI, DNS names, messages, video titles or application payloads. No destinations/flow metrics in UI/files/logs. No real-child packet capture. Strip native logger/stdout/stderr/error printing and mapped-DNS functionality. Stop clears buffers and flow maps.

Authorized Normal policy removes artificial cap/delay with a bounded fair drain and without resetting transport. A call pause alone retains the current policy for non-excluded traffic. Fixed exclusions provide app-wide bypass, not proof of universal call protection.

## Event scheduling

TimeProvider maps elapsedRealtime into the domain. Schedule only the next mode/ramp boundary while a live process can execute. A visible dashboard ticker updates display, never session state by decrementing a counter.

At wake, network activity and packet-policy evaluation, compare the latest elapsed snapshot to the policy deadline before forwarding under an old unrestricted grant. Posted delays are wake opportunities, not the time authority. No requirement for exact-alarm permission or a permanent wake lock. Under continuous traffic the packet loop can apply elapsed policy without an independent polling thread; while idle there is no network experience to dull.

Call pause retains the pre-pause next play/ramp/expiry boundary as a mandatory one-shot fresh-state revalidation checkpoint; it does not cancel that fallback. Revalidate earlier on relevant lifecycle, permission, subscription, registration, or monitor-restart events. Cached ACTIVE state cannot renew a pause. At the checkpoint, fresh INACTIVE/UNAVAILABLE or query failure closes the pause and resumes countdown from preserved remaining time; fresh ACTIVE establishes only the next projected boundary checkpoint. This is event/boundary driven, not a wake loop. Monitor and authorization generations guard delayed callbacks, and unavailable observation cannot preserve a cached pause.

## Acceptance boundaries

Separate proofs are required for authorization intent, actual applied forwarding policy, and physical call behavior. A mock VpnState.RUNNING, granted consent, system key icon or live notification proves none of the shaping/profile/call guarantees alone.

Physical qualification includes both OS versions; Wi-Fi/cellular; dual stack; TCP/UDP/QUIC; simultaneous flows; mobile/Telecom calls on single and dual SIM; WhatsApp and game voice calls produce no pause; unavailable detection/permission; overlapping observations; locked child calls; parent screen-off; revoke/stop/restart/boot; excluded-app (dialer, SMS/RCS, WhatsApp) versus non-excluded traffic; default SMS app change applied at next establish; 4 KB/16 KB native packaging; no application output in error paths.

Keep all source/license hashes, synthetic measurement reports and gate results in development artifacts, never the installed settings document.

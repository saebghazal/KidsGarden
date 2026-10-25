<!--
Sync Impact Report
==================
Version change: 3.1.1 → 4.0.0 (2026-10-25)
Bump rationale: MAJOR, a principle rule is redefined. Principle I said an unlock alone never
grants normal use. The owner added Unlock grace (spec FR-037): when the parent has it on, an
unlock with no grant gives a short grace that fades into Dull Mode, at most once per rest
period. The rest-period start time is the one persisted value about use. Principle III and VI
wording now allow that single timestamp. Default on, set by the owner.

Previous amendment (3.1.0 → 3.1.1, 2026-10-18):
Bump rationale: PATCH, wording only. Principles I, III and VI said V1 stores "session state";
the spec (FR-013, FR-027) keeps sessions in memory only. Wording now says V1 persists parent
settings plus the saved brightness restore value (FR-034) and nothing about sessions. No rule
changed. Found by /speckit-analyze on 2026-10-18 (finding C1).

Previous amendment (3.0.0 → 3.1.0, 2026-10-18):
Bump rationale: MINOR, materially expanded guidance. Dull Mode may now also use gentle,
parent-configured screen and sound effects (grey tint, lower media volume, lower brightness)
that fade in and out in cycles, alongside or instead of VPN network dulling (spec FR-034–036,
clarification 2026-10-18). No principle removed or redefined.

Modified principles (titles unchanged):
- II. No Confrontation, Gradual Transitions: Dull Mode methods expanded; effects must be gradual,
  touch-transparent, never lock or hide apps, and never touch call/alarm audio.
- V. Honest Android Platform Compliance: overlay and system-settings access named among the
  special accesses that need a prior parent explanation.
Other amended sections:
- Technical Constraints and V1 Scope: screen and sound dulling added to V1 scope (in).
Added sections: none
Removed sections: none

Write scope: .specify/memory/constitution.md only.
Follow-up: plan.md, tasks.md, data-model.md and contracts still need FR-032–036.
Previous amendment (2.0.0 → 3.0.0, 2026-10-15): Active/Deactivated switch in Principle I.

Deferred placeholder TODOs: none.
This report is temporary amendment-review material; remove it before committing the constitution
after the existing maintainer review requirement is satisfied.
-->

# Kids Garden Constitution

## Core Principles

### I. Unauthorized = Dull Phone (Fail-Safe Default)

- Dull Mode MUST be the default while Kids Garden is Active. While Active, no valid Kids Garden
  authorization MUST mean Dull Mode. A normal device unlock, app launch, or successful PIN entry
  alone MUST NOT grant normal use, except the parent-configured Unlock grace below.
- Unlock grace (v4.0.0): while Kids Garden is Active and the parent has it switched on, an
  unlock from Dull Mode MAY give a short, parent-set grace that then fades gradually into Dull
  Mode, at most once per parent-set rest period. It is never shown to the child as a message,
  never applies during Parent Mode, play time or Deactivated, and never survives a restart as a
  grant. Only its start time is saved, so a restart cannot reset the rest period.
- The parent MAY turn Kids Garden off with an Active/Deactivated switch. Deactivating MUST require
  parent-interface access obtained with the Kids Garden PIN plus an explicit confirmation; it ends
  every grant and stops dulling until the parent activates again. Activating MUST require the
  PIN and returns the phone to Dull Mode without creating a grant. The choice is a saved parent
  setting and persists across restart, update, and reboot. Nothing other than an authenticated
  parent action MAY deactivate Kids Garden: missing, corrupt, or unmigratable settings, errors,
  restarts, and child actions MUST resolve to Active and Dull Mode.
- Enjoyable use MUST be allowed only through one of two explicit Kids Garden authorizations:
  untimed Parent Mode for the parent or a timed Child Play Session for the child. Each new grant
  MUST require the Kids Garden PIN followed by the corresponding explicit parent action.
- Parent Mode MUST begin only after the parent enters the Kids Garden PIN and chooses
  “Use phone as parent.” It MUST provide normal or near-normal phone use with no time limit.
- Parent Mode MUST end when the phone locks, the screen turns off, the parent exits, app state
  becomes invalid, or the owning app process restarts unexpectedly. After lock/unlock, the phone
  MUST remain in Dull Mode until the parent enters the Kids Garden PIN again and explicitly
  starts a new authorization. Unlock alone MUST NOT restore Parent Mode.
- A Child Play Session MUST begin only after the parent enters the Kids Garden PIN, chooses
  “Give phone to child,” and selects a limited duration. The session MUST have a timer.
- Lock/unlock MUST NOT reset, pause, extend, or bypass the child timer. A scoped detectable-call
  pause is the only automatic pause: normal mobile/Telecom calls where Android APIs permit pause
  the timer only while a supported active call is detectable. WhatsApp and other third-party app
  calls, and unsupported or undetectable calls, MUST leave countdown running. A call pause MUST preserve the
  remaining time without duplicate credit; when no supported active call remains detectable,
  countdown MUST resume from that remainder. Lock/unlock alone MUST NOT alter a valid call pause.
- Near normal child-session expiry, Soft Landing MUST begin. At expiry, after accounting for
  validated pauses, the phone MUST return to Dull Mode. Calls MUST NOT revive expired or revoked
  authorizations or create an unlimited-play loophole. A new grant requires a new PIN/action.
- The app MUST fail safe into Dull Mode when settings are missing, unreadable, corrupt, from an
  unknown schema version that cannot be migrated, or when the in-memory session state is invalid or
  cannot be validated (for example, an inconsistent deadline or clock).
- Process death, app updates, and device reboots MUST NOT extend, restore, or create authorization.
  V1 MUST discard all active grants and current child-session timestamps on owning-process
  restart, update, or reboot. Valid parent settings MUST be loaded and re-validated; persisted
  mode or session data MUST NOT authorize normal use. Healthy UI recreation alone is not a
  process restart.

Rationale: the product's core promise is that entertainment is something a parent grants, not
something the child keeps by default or regains through errors.

### II. No Confrontation, Gradual Transitions

- The child-facing experience MUST NOT contain blocking messages, punishment screens, warnings,
  countdown threats, scary alerts, or adversarial language ("blocked", "denied", "violation",
  "caught", and similar).
- Normal Child Play Session expiry MUST be gradual and quiet (Soft Landing): internet
  responsiveness degrades over time rather than switching off abruptly. Parent Mode exit and
  invalid/revoked authorization MUST return immediately to Dull Mode without child-facing
  confrontation; Soft Landing MUST NOT extend a revoked grant.
- Any notification required by Android (for example, a foreground service or VPN notification)
  MUST use calm, neutral wording and MUST NOT address or scold the child.
- Problems (missing permission, VPN failure, throttling not active) MUST be explained only in the
  PIN-protected parent interface, never surfaced to the child as a confrontation.
- Dull Mode MUST make entertainment less attractive by reducing internet responsiveness and
  bandwidth through a local VPN-style approach and/or by parent-configured screen and sound
  effects (a see-through grey tint, lower media volume, lower brightness). It MUST NOT lock the
  device or prevent use of essential functions such as phone calls and emergency services.
- Screen and sound effects MUST be gradual (fading in and out, never switching on at once), let
  every touch pass through, never hide, block, or target specific apps or content, never show
  text to the child, and never change call, ringtone, alarm, or accessibility audio. They stop
  during detectable phone calls and outside Dull Mode, and restore the parent's settings.

Rationale: confrontation between a child and software creates conflict, workarounds, and distrust;
a dull phone that quietly stays dull avoids all three.

### III. Privacy by Absence

- The app MUST NOT collect, read, store, or transmit browsing content, URLs, DNS queries, video
  titles, messages, notifications, screenshots, screen contents, keystrokes, search queries, contact
  data, location, or any other private content.
- Network handling in the local VPN MUST be limited to shaping rate and latency. It MUST NOT
  inspect application-layer payloads, and it MUST NOT persist any record of traffic, destinations,
  or apps used.
- V1 persists only (a) parent settings and (b) the parent's previous screen brightness while a
  brightness effect is applied, so it can be restored, and (c) the start time of the last Unlock
  grace (v4.0.0), a single overwritten timestamp so a restart cannot reset the rest period. Session
  and authorization state are kept in memory only. Activity logs, history, and usage reports are prohibited in V1.
- V1 MUST NOT create logs in debug or release builds, including Logcat diagnostics, crash logs,
  PIN-attempt logs, network logs, call logs, or activity logs. Debug builds MUST NOT weaken this
  rule. External test tooling may retain synthetic test results outside the installed product;
  it MUST NOT collect real child content or usage history.
- The Parent PIN MUST NOT be stored in plain text. Store a salted hash only.
- No hidden surveillance: every capability the app uses MUST be visible to and explained to the
  parent.

Rationale: the safest data is data that never exists. Parents can trust the app because it cannot
leak what it never collected.

### IV. Central, Testable State Machine

- Authorization behavior (Dull → Parent Mode → Dull, Dull → Child Play Session → Soft Landing
  → Dull, and Active ⇄ Deactivated), including scoped call pauses and revocation, MUST be modeled as one explicit state
  machine in the domain layer, written in pure Kotlin with no Android framework dependencies.
- Time MUST be injected (for example, through a clock abstraction) so that expiry, Soft Landing
  steps, and invalid-clock handling can be unit tested deterministically.
- Every state, transition, and fail-safe rule MUST have unit tests. A change to the state machine
  without matching tests MUST NOT be merged.
- Platform components (VPN service, alarms, boot receiver, UI) MUST react to state machine output.
  They MUST NOT implement their own session logic.

Rationale: the state machine is the product. Keeping it central and pure makes it verifiable and
keeps the fail-safe guarantees in Principle I enforceable.

### V. Honest Android Platform Compliance

- The app MUST comply with Android 15 and Android 16 rules for background execution, foreground
  services (including declared service types), `VpnService`, notifications, exact alarms, and
  runtime permissions. It MUST NOT use workarounds that rely on undocumented behavior.
- Background behavior MUST be event and rule driven (system alarms, scheduled work, boot and package
  broadcasts, VPN lifecycle callbacks). Continuous polling, AI, and screen analysis are prohibited.
- Every sensitive permission or special access (VPN consent, notification permission, battery
  optimization exemption, exact alarms, display over other apps, modify system settings, and
  similar) MUST have a clear, plain-language,
  parent-facing explanation shown before the system prompt.
- V1 is a normal installed app. It MUST NOT claim or advertise uninstall prevention, tamper-proofing,
  or protection it cannot reliably enforce. The parent interface MUST honestly describe V1 limits.
- Network dulling MUST be described as best-effort. V1 MUST NOT promise full OS control, guaranteed
  dulling of cached/offline content, or continuous protection after force-stop, uninstall, or VPN
  revocation. Scoped call detection MUST NOT be represented as universal call detection.
- Strong tamper protection (Device Owner / DPC mode) is out of scope for V1 and reserved for V2.

Rationale: platform-compliant apps keep working across OS updates and Play policy reviews.
Overpromising protection misleads parents.

### VI. Local-Only and Free

- No backend, remote server, cloud sync, or account system. Authentication is the local Parent PIN
  only.
- No database. Settings (including the brightness restore value and the last Unlock grace start) are stored with Jetpack DataStore using a JSON serializer
  (kotlinx.serialization), accessed only through a settings repository in the data layer.
- No analytics, crash-reporting SDKs that transmit data, advertising SDKs, or tracking of any kind.
- The app is entirely free: no in-app purchases, subscriptions, or ads.
- The app MUST NOT request the network for its own purposes. The VPN only shapes traffic the device
  already produces.

Rationale: a parental tool with no server has no server-side breach, cost, or data-retention risk.

### VII. Simplicity and Battery Efficiency

- Prefer the simplest design that satisfies the spec (YAGNI). New dependencies, modules, or
  abstractions MUST be justified in the plan's Complexity Tracking.
- The codebase MUST stay small and conventional enough for a small team working with Claude Code
  and Codex: standard Android project layout, idiomatic Kotlin, and clear layer boundaries.
- Battery use MUST be minimized: no wake loops, no busy timers, and the VPN packet path MUST be
  lightweight. Work is scheduled only when a state transition requires it.

Rationale: simple code is easier to verify, easier for AI agents to change safely, and cheaper on
the device's battery.

## Technical Constraints and V1 Scope

**Platform and tooling**

- Android phones only. `minSdk` 35 (Android 15), `targetSdk` 36 (Android 16).
- Language: Kotlin. UI: Jetpack Compose. IDE: Android Studio. Build: Gradle with the Kotlin DSL.
- Architecture layers: `ui` (Compose screens and ViewModels), `domain` (state machine, use cases,
  pure Kotlin), `data` (settings repository over DataStore JSON), and `platform` (VPN service,
  alarms or scheduling, boot receiver, permission helpers). Dependencies point inward toward
  `domain`.

**Settings schema**

- The JSON settings document MUST carry an explicit `schemaVersion` field.
- Each schema change MUST bump `schemaVersion` and ship a migration from every previously released
  version, with a unit test for each migration.
- Unknown, newer, or unmigratable versions MUST resolve to Dull Mode (Principle I) and be reported
  in the parent interface.

**V1 scope (in)**

- Parent PIN setup and verification.
- Explicit PIN-authorized “Use phone as parent” for untimed Parent Mode, ending on lock,
  screen off, parent exit, invalid state, or owning-process restart.
- Explicit PIN-authorized “Give phone to child” for a timed Child Play Session of a selected
  duration, with scoped detectable-call pauses and no lock/unlock timer bypass.
- Dull Mode without valid authorization while Active; Soft Landing near normal child expiry, then
  Dull Mode.
- PIN-protected Active/Deactivated switch; Deactivated stops dulling and persists until the
  parent activates again.
- Local VPN-based throttling of internet responsiveness and bandwidth.
- Optional, gradual screen and sound dulling (grey tint, lower media volume, lower brightness)
  configured by the parent, usable with or without the VPN.
- Parent-only status screen that reports permission or VPN problems.

**V1 scope (out)**

- Device Owner / DPC mode, factory-reset provisioning, uninstall prevention.
- Schedules, automatic sessions, remote control, multi-device management.
- Logs, history, usage reports, content filtering by category or content.

**Distribution**

- V1: private APK / sideload or a Play internal testing track. A future free public release is
  possible and MUST NOT require architectural changes to the privacy guarantees.

## Development Workflow and Quality Gates

- Work follows the Spec Kit flow: specify → (clarify) → plan → tasks → implement. Every plan MUST
  pass the Constitution Check against Principles I–VII before implementation.
- Every feature MUST be testable and ship with tests:
  - Unit tests for all state machine states, transitions, and fail-safe rules (mandatory).
  - Unit tests for both explicit authorization forms, Parent Mode revocation, child lock/unlock
    invariance, scoped call pause/resume, unsupported-call countdown, restart invalidation, and
    the Active/Deactivated switch (deactivate from every state, persistence, safe-default Active).
  - Unit tests for settings serialization, defaults, corruption handling, and every schema
    migration.
  - Instrumented or manual test steps for platform behavior that cannot be unit tested (VPN
    consent, reboot recovery, foreground service behavior), recorded in the feature's tasks.
- Review checklist for every change (human or AI-authored):
  - Does any path leave the device unrestricted without parent authorization or an authenticated,
    confirmed Deactivate? Can anything other than the parent deactivate? (Principle I)
  - Does any child-facing text or UI confront the child? (Principle II)
  - Is any private content read, stored, logged, or transmitted? (Principle III)
  - Does either a debug or release path create app logs? (Principle III)
  - Is session logic outside the state machine? (Principle IV)
  - Are new permissions explained to the parent and compliant with Android 15/16? (Principle V)
  - Was any network, analytics, or database dependency added? (Principle VI)
  - Is added complexity justified, and is battery impact acceptable? (Principle VII)
- Claude Code and Codex follow the same rules as human contributors. AI-generated changes MUST
  pass the same tests and review checklist before merge.

## Governance

- This constitution supersedes all other project practices, guides, and agent instructions. When a
  conflict exists, the constitution wins until amended.
- Amendments are made through `/speckit-constitution`, MUST include a Sync Impact Report, and MUST
  be reviewed by a project maintainer before they are committed.
- Versioning follows semantic versioning:
  - MAJOR: removal or backward-incompatible redefinition of a principle or governance rule.
  - MINOR: a new principle or section, or materially expanded guidance.
  - PATCH: clarifications, wording, and typo fixes with no semantic change.
- Compliance is checked at plan time (Constitution Check) and at review time (checklist above). Any
  justified exception MUST be recorded in the plan's Complexity Tracking with the simpler
  alternative that was rejected and why.
- Moving a V2 capability (for example, Device Owner mode or logs) into scope requires a constitution
  amendment first.

**Version**: 4.0.0 | **Ratified**: 2026-10-09 | **Last Amended**: 2026-10-25

# Privacy Review Checklist: Kids Garden V1

**Purpose**: Review gate for every change (Constitution III, VI; spec Privacy Constraints, NFR-005, NFR-008). Reviewer-owned: `[x]` means the reviewer checked the item for the change under review.

**How to use**: Run the searches from the repository root, review every hit in context, then tick the items. Hits are evidence to review, not automatic failures.

```powershell
rg -n 'android\.util\.Log|Timber|println|printStackTrace|__android_log|fprintf|printf' app domain
rg -n 'RoomDatabase|sqlite|FirebaseAnalytics|Crashlytics|analytics' app domain
rg -n 'isInCall|AudioManager|QUERY_ALL_PACKAGES|READ_CALL_LOG|READ_CONTACTS|NotificationListener|AccessibilityService|UsageStats' app domain
rg -n 'Text\(\s*"' app/src/main
.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath
```

## Logging and telemetry

- [ ] No logging calls in main or debug source sets, including native code
- [ ] No analytics, crash-reporting, advertising, or tracking dependency in the release classpath
- [ ] Error paths return coarse reason codes; no exception text, document bytes, or traffic data is printed

## Storage

- [ ] Only `SettingsDocument` fields are persisted; no mode, session, pause, token, or call field
- [ ] No raw PIN anywhere: storage, saved state, navigation arguments, bundles, logs
- [ ] No database, extra files, caches of content, or history in app-owned storage
- [ ] Backup and device-transfer exclusions are intact (`allowBackup=false`, extraction rules)
- [ ] About use, only the unlock-grace window start (`lastUnlockGraceEpochMillis`, one overwritten value) is stored, and it is cleared by Activate, Deactivate and Parent Mode (FR-037)

## Content and traffic

- [ ] No URL, domain, DNS name, SNI, payload, message, notification, or screen content is read or stored
- [ ] Packet handling uses only headers needed for forwarding; buffers and flow maps are bounded and cleared
- [ ] No app-originated connection to any remote service

## Calls and packages

- [ ] Call detection uses per-subscription telephony state only; no `TelecomManager.isInCall`, device-wide call state, or `AudioManager` mode
- [ ] No caller number, contact, SIM identifier, or call history is read or retained
- [ ] Package visibility is limited to the declared `<queries>`; no broad app inventory
- [ ] The launcher app list for "Apps that skip Kids Garden" is read only while that parent screen is open and never stored; only the package names the parent checks are saved (FR-038)
- [ ] Apps that skip Kids Garden are only excluded from the VPN; their traffic is not inspected, counted or recorded

## Permissions and copy

- [ ] Each sensitive permission (VPN, notifications, phone state) has a parent explanation shown before the system prompt
- [ ] All user-visible text comes from `strings.xml`; no hard-coded Compose text
- [ ] No child-facing blocking, warning, or blame wording; notifications stay neutral
- [ ] Unlock grace and its countdown appear only inside the PIN-protected app, never in the status bar, a notification or the PIN screen

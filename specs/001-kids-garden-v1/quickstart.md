# Quickstart and Validation: Kids Garden V1

## Current repository status

Updated 2026-10-11. The app and its tests are written for every user story (86 of 94 tasks). The first local build on Windows passes:

| Check | Result |
| --- | --- |
| `:domain:test` | 117 of 117 pass |
| `:app:testDebugUnitTest` | 75 of 75 pass |
| `:app:lintDebug` | 0 errors |
| `:app:compileDebugAndroidTestKotlin` | compiles |
| `:app:assembleDebug` | builds; `libkg-tunnel.so` packaged for arm64-v8a and x86_64, LOAD segments 16 KB aligned |

**Nothing has run on a device yet**: the instrumented suite, forwarding qualification and every manual check are still pending, so every gate in the [gate register](plan.md#gate-register) is still OPEN.

### Getting the code on Windows

The forwarding engine is a pinned git submodule (`app/src/main/cpp/hev-socks5-tunnel`, upstream `07bea57`) whose public headers are symlinks. Git on Windows checks symlinks out as one-line text files unless they are enabled, and the C build then fails. Turn on **Developer Mode** (Settings → System → For developers), then clone:

```powershell
git -c core.symlinks=true clone --recurse-submodules https://github.com/saebghazal/KidsGarden.git
```

For an existing clone whose submodules were checked out without symlinks:

```powershell
git submodule foreach --recursive "git config core.symlinks true && git checkout -- ."
```

The build stops early with this instruction if it finds the headers as text files. Without the submodule the app still builds and reports the VPN as unavailable.

Only normal mobile/Telecom calls pause play; WhatsApp, the dialer and the default SMS/RCS app are excluded from dulling but never pause play. See [research R1](research.md#r1--scoped-v1-call-handling-telephony-only).

## Prerequisites

- Android Studio. Gradle 8.13 runs on Java 17–23; `gradle/gradle-daemon-jvm.properties` asks for Java 21 because Android Studio bundles Java 25. Install a JDK 21 (for example via Android Studio's *Download JDK*).
- SDK platform API 36 (compile/target 36, min 35) and NDK (Side by side) `28.2.13676358`, from Android Studio's SDK Manager.
- Gradle 8.13 wrapper (committed), AGP 8.11.1, Kotlin 2.2.21, versions pinned in `gradle/libs.versions.toml`.
- Two physical test phones covering Android 15/16, plus emulators for UI/edge cases.
- A separate test caller and approved non-emergency calling fixtures. Do not place live emergency calls.
- Controlled synthetic network fixtures outside the app; no production backend or real-child traffic capture.
- Verify the native library on 4 KB and 16 KB page-size environments.

Use Android Studio's SDK configuration or local.properties locally (`sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk`); do not commit developer paths/signing keys.

## Build and automated checks (PowerShell)

```powershell
Set-Location 'C:\A-Proj\KidsGarden'
.\gradlew.bat --version
.\gradlew.bat :domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Expected: pinned toolchain resolves, domain has no Android imports, unit/lint/UI tests pass and debug APK exists. Missing call permission/unsupported detection continues countdown and working dulling. Lab fakes must not be compiled into production paths or exposed through exported commands.

For installation on a chosen test device:

```powershell
adb devices
adb -s DEVICE_SERIAL install -r app\build\outputs\apk\debug\app-debug.apk
adb -s DEVICE_SERIAL shell am start -n com.kidsgarden.app/.MainActivity
```

Replace DEVICE_SERIAL with the listed test device. This guide does not issue these commands itself.

Dependency/privacy inventory:

```powershell
.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath
rg -n 'android\.util\.Log|Timber|println|printStackTrace|__android_log|fprintf|printf|RoomDatabase|sqlite|FirebaseAnalytics|Crashlytics|isInCall|AudioManager|QUERY_ALL_PACKAGES' app domain
rg -n 'Text\(\s*"' app/src/main   # English-only: user-visible Compose text must come from stringResource
```

Treat search hits as review evidence, not automatic proof/failure. Audit the merged manifest, native libraries, dependency sources, stdout/stderr and app-specific error paths too. Never print raw production settings/PIN or real traffic to verify a privacy promise. Test reports may contain synthetic cases only.

## Required scenarios and expected outcomes

Use [data-model.md](data-model.md) and [state-machine contract](contracts/state-machine.md) for detailed invariants.

| Scenario | Procedure | Expected |
| --- | --- | --- |
| Setup/privacy | Fresh test install, create/confirm PIN, read explanations, deny then accept VPN consent | No raw PIN/account; no grant on consent; truthful limited status if capability fails |
| Default DULL | Complete setup in a qualified fixture; open apps and lock/unlock without PIN | DULL authorization remains; actual shaping only if all gates satisfied |
| Parent Mode | Correct PIN → explicit parent action; use other app; screen off/lock/unlock | Normal during valid grant; DULL after lock; no timer |
| Parent call | Start parent grant; incoming/outgoing call; end without revocation | Parent grant remains; no confirmation |
| Parent lock during call | Repeat, deliberately screen off/lock | Parent grant revoked; call end cannot restore it; fixed package exclusions remain |
| Child durations | Run 10/20/30 choices; repeat lock/unlock at least 10 times each OS | Lock alone never pauses/resets/extends time |
| Child handoff | Start then press Back/reopen/notification/deep link | Parent controls immediately require fresh PIN |
| Preserved child time | 30-minute grant, use 5 minutes, normal phone call for 25 minutes | 25 minutes remain at call end; resumes silently |
| Overlap/missed calls | Dual-SIM or call-waiting overlap; separately test ringing-only/missed/declined | One union pause for phone calls; ringing-only has no credit |
| Paused Soft Landing | Phone call during ramp with 45 seconds remaining | Same 45 seconds and prior progress resume, no warning |
| DULL call | Phone call and WhatsApp call while DULL | Dialer and com.whatsapp bypass shaping where possible; other traffic stays dull throughout |
| Expiry while locked | No active call; lock across effective deadline | DULL on next evaluation; no grace/renewal |
| Clock/race/error | Fake elapsed/wall shifts, expired event races, duplicate writes, invalid timestamps | No unauthorized access; unclear state becomes DULL |
| Persistence | Serializer fixtures, missing/corrupt/future schema, I/O failure | Safe defaults or explicit unavailable storage; never false save success |
| Process/reboot/update | Restart owning process, update, or reboot during any grant/pause | New process starts at DULL with no session; valid settings persist; settings file contains no mode/session fields |
| VPN lifecycle | Revoke consent, activate other VPN, stop service | DULL authorization plus accurate limited status; no intentional blackhole |
| Third-party calls | WhatsApp call and a game voice chat during a child session | No pause: countdown, Soft Landing and expiry continue; WhatsApp call quality not slowed |
| Missing call permission | Deny/revoke permission before and during a pause | No new pause; close affected active pause eligibility and resume remainder; exact parent-only limitation |
| Paused lock/unlock | Detectable active call, repeated locks/unlocks, then end call | Same remainder while paused; resumed countdown never resets or gains duplicate credits |
| Stale call state | Lose/restart adapter; deliver old active/end callbacks | Cached pause is not restored; countdown continues unless a current valid active source exists |
| Lost call-end callback | Suppress the end callback, then reach the retained pre-pause boundary | Mandatory fresh query closes the pause on inactive/unavailable/failure; cached ACTIVE cannot renew it or create unlimited play |
| Communication exclusions | Default/system dialer, default SMS/RCS app (send an RCS photo in DULL), installed/missing com.whatsapp, change default SMS app then restart VPN, unrelated traffic | Each resolvable package excluded; missing one skipped; new SMS default applied at next establish; no whole-device bypass or content inspection |

Synthetic fast-forward covers full duration/race space. Also run real elapsed-duration sessions on devices; emulator telephony injection alone cannot prove physical calls, third-party signaling or OS lifecycle.

## Profile/forwarding qualification (isolated lab only until gates pass)

Use synthetic TCP and UDP/QUIC traffic on Wi-Fi/cellular, IPv4/IPv6 and dual stack. Compare VPN-off baseline with Normal and each dull profile, five matched runs each. Check concurrent-flow aggregate limits, latency definition, resolver transport, network handover, captive portal, MTU and sustained bounded queues.

Normal: median throughput ≥90% baseline and added median RTT ≤50 ms. Dull targets must remain within the spec's 20% calibration bound; stronger profiles show stronger dulling. Verify Gentle Soft Landing never enters Medium, and authorized Normal policy clears artificial delays without resetting connections.

Measure two-hour screen-off idle battery against a matched baseline (≤2 extra percentage points), active-load CPU/memory, native footprint and 4 KB/16 KB compatibility. Record external lab results only. No app-created metrics/history.

Missing/unavailable call detection shows only this parent-facing call limitation: “Play time pauses during normal phone calls. Calls in other apps, such as WhatsApp, are not slowed down but do not pause play time.” No child message or setup/VPN block is created.

## Test suites to create

- domain: every transition, gate, stale generation, all pause/resume/overlap cases, monotonic stage policy and corrupted time.
- app unit: JSON/defaults/corruption/schema migration/I/O failure, legacy mode/childSession key drop, no authorization fields written, correct/wrong PIN, distinct salts, invalid work factors, no raw PIN representation.
- app instrumentation: ten requested screens, navigation gates, permission explanation screens before each prompt, font scaling/accessibility, foreground/consent/revoke behavior and runtime broadcasts.
- device QA: mobile/Telecom calling (single and dual SIM), WhatsApp/game voice no-pause, communication-app exclusions, permission fallback, reboot/update/force-stop, actual forwarding and performance; fakes do not substitute for scoped device evidence.

Refer to [parent UI](contracts/parent-ui.md) and [Android VPN](contracts/android-vpn.md) contracts instead of implementing duplicate policies in tests.

## Private release validation

Only after scoped V1 acceptance and all remaining applicable release gates pass:

```powershell
.\gradlew.bat :domain:test :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

Configure signing through local/secure build inputs; keep keys and passwords out of source and command output. Verify the signed APK on both OS versions. Inspect merged permissions, backup rules, runtime dependencies, Kotlin/native output and limitation text. Confirm no database, backend, cloud account, analytics, logs, content collection or raw PIN. Prefer a private APK; internal testing is an optional later distribution route, not a requirement to publish now.

A build that merely installs is not a released Kids Garden V1. Scoped call behavior plus actual forwarding, lifecycle, privacy and quality evidence are required; universal detection is not a release gate.

## Private APK signing and sideloading

> **Not for release yet.** These steps are recorded now so the release path is ready, but a
> signed APK must not be given to families until every gate in the
> [gate register](plan.md#gate-register) is green. Until then, use them only for test devices.

### 1. Create the signing key, outside the repository

Keep the key and its passwords out of the repository and out of shell history. `.gitignore`
already excludes `*.jks`, `*.keystore` and `keystore.properties`, but the key belongs in a
separate folder entirely:

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\.kidsgarden" | Out-Null
keytool -genkeypair -v -keystore "$env:USERPROFILE\.kidsgarden\kidsgarden-release.jks" `
  -alias kidsgarden -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` prompts for the passwords and the certificate name; it ships with the JDK that
Android Studio uses (`<Android Studio>\jbr\bin`).

**Back up the `.jks` file and both passwords somewhere safe and offline.** Every update must be
signed with the same key. If the key is lost, the app cannot be updated: it has to be
uninstalled and reinstalled, which deletes its settings and PIN.

### 2. Point the build at the key

Create `%USERPROFILE%\.kidsgarden\signing.properties` (or set the environment variable
`KIDSGARDEN_SIGNING_PROPERTIES` to another path):

```properties
storeFile=C:/Users/<you>/.kidsgarden/kidsgarden-release.jks
storePassword=<store password>
keyAlias=kidsgarden
keyPassword=<key password>
```

`app/build.gradle.kts` reads this file only when it exists. Without it, release builds are
produced unsigned and cannot be installed.

### 3. Build and verify

```powershell
.\gradlew.bat :domain:test :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\<version>\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk
```

Check that the certificate printed is yours, then complete the checks in "Private release
validation" above on both Android 15 and Android 16.

### 4. Install on the child's phone

With USB debugging (Settings → System → Developer options → USB debugging):

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

Without a computer: copy the APK to the phone, open it from the Files app, and allow
"Install unknown apps" for that app when Android asks. Turn that permission off again
afterward. Updates install the same way and keep settings only when signed with the same key.

After installing, open Kids Garden as the parent and complete setup. Turn off USB debugging
on the child's phone when you are done.

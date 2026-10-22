# QA Results (T092, G-QUALITY)

**Status**: Automated suites PASS on one API 36 phone. The quickstart scenario table has not
been run, and there is no API 35 device yet, so T092 and G-QUALITY stay open.

## Automated suites — 2026-10-12

Build: branch `001-kids-garden-v1` at `da78949` (same code as `main` at `2d2f6f4`), debug,
JDK 21, Windows 11.

| Suite | Command | Result |
| --- | --- | --- |
| Domain (JVM) | `:domain:test` | 120/120 pass |
| App unit (JVM) | `:app:testDebugUnitTest` | 76/76 pass |
| Lint | `:app:lintDebug` | 0 errors, 20 warnings (dependency/AGP versions, no app icon) |
| Instrumented | `:app:connectedDebugAndroidTest` | 43/43 pass on Samsung Galaxy A73 (SM-A736B), Android 16 / API 36 |

Instrumented tests per class: AccessibilityTest 6, ChildSessionFlowTest 4,
DashboardSettingsTest 13, ParentAccessGuardTest 3, ParentModeFlowTest 7, SetupFlowTest 6,
SoftLandingTest 2, StoragePrivacyTest 2.

### Run notes

- First full run: 35 passed, 8 failed. All 8 (5 in AccessibilityTest, 3 in
  ChildSessionFlowTest) failed with `IllegalStateException: No compose hierarchies found`
  because the phone was on its lock screen while they ran. No assertion failed.
- Rerun of those two classes with the phone unlocked: 10/10 pass.
- The phone must stay unlocked for the whole instrumented run. `adb shell svc power stayon usb`
  keeps the screen on but does not prevent the lock screen.

## Not yet run

- API 35 device (none available).
- Quickstart scenario table (`quickstart.md`, "Scenario" table): manual device scenarios.
- Performance and battery (T090), user studies (T091).

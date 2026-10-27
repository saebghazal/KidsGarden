# Installing Kids Garden

This guide takes you from a fresh computer to Kids Garden running on your own Android phone or tablet. There are two ways to do it:

- **[Part A](#part-a-build-it-yourself): build it yourself** from source in Android Studio, if you want to run, change or contribute to the code.
- **[Part B](#part-b-install-a-ready-made-apk-no-developer-tools): install a ready-made APK** that someone else built, with no developer tools.

Then [Part C](#part-c-first-time-setup-on-the-phone) sets up the app on the phone, and [Troubleshooting](#troubleshooting) covers the usual problems.

> Kids Garden is a test-stage app. It is not on Google Play yet. Debug builds are signed with your own computer's debug key, which is fine for testing on your own devices.

---

## What you need

**A phone or tablet**

- Android **15 or 16** (the app does not install on older Android).
- A normal 64-bit (arm64) processor. Almost every phone or tablet from the last few years qualifies. An x86_64 Android emulator also works for development.
- A USB cable, if you install from a computer.

**A computer** (only for Part A): Windows, macOS or Linux with:

| Tool | Version | Where to get it |
| --- | --- | --- |
| Git | any recent | [git-scm.com](https://git-scm.com/) |
| Android Studio | current stable | [developer.android.com/studio](https://developer.android.com/studio) |
| JDK | **21** (17–23 also work) | Android Studio → Settings → Build Tools → Gradle → Gradle JDK → *Download JDK* |
| Android SDK Platform | **API 36** | Android Studio → SDK Manager → SDK Platforms |
| Android NDK (Side by side) | **28.2.13676358** | SDK Manager → SDK Tools → tick *Show Package Details* → NDK |

Gradle, the Android Gradle Plugin and Kotlin are downloaded automatically by the Gradle wrapper. Their versions are pinned in `gradle/libs.versions.toml`.

---

## Part A: build it yourself

### 1. Get the code (with its submodule)

The local VPN uses the [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) library as a **git submodule**, so clone with `--recurse-submodules`.

**macOS / Linux**

```bash
git clone --recurse-submodules https://github.com/saebghazal/KidsGarden.git
```

**Windows**: the submodule uses symbolic links. Turn on **Developer Mode** first (Settings → System → For developers), then clone with symlinks enabled:

```powershell
git -c core.symlinks=true clone --recurse-submodules https://github.com/saebghazal/KidsGarden.git
```

If you already cloned without submodules or without symlinks, run this inside the project folder:

```bash
git submodule update --init --recursive
git submodule foreach --recursive "git config core.symlinks true && git checkout -- ."
```

The build stops early with a clear message if the symlinks are missing.

### 2. Open it in Android Studio

1. **File → Open** and choose the `KidsGarden` folder (the one with `settings.gradle.kts`).
2. Let Gradle sync finish. If it asks, install SDK Platform 36 and NDK `28.2.13676358`.
3. Make sure **Settings → Build Tools → Gradle → Gradle JDK** is a JDK 21.

### 3. Build and test from the command line (optional)

**macOS / Linux**

```bash
./gradlew :domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

**Windows (PowerShell)**

```powershell
.\gradlew.bat :domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

This runs the unit tests, runs lint, and builds the app file at `app/build/outputs/apk/debug/app-debug.apk`.

### 4. Prepare your phone or tablet

1. **Settings → About phone/tablet → Software information** (the path varies by brand), then tap **Build number** seven times. "Developer mode has been turned on" appears.
2. **Settings → Developer options** → turn on **USB debugging**.
3. Connect the device to the computer with USB and tap **Allow** on the "Allow USB debugging?" prompt.

### 5. Install and run

- **In Android Studio:** pick your device in the device list at the top, then press **Run ▶**.
- **Or from the command line**, using `adb` from the Android SDK `platform-tools` folder:

```bash
adb devices
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`-r` updates an existing install and keeps its settings. Continue with [Part C](#part-c-first-time-setup-on-the-phone).

### 6. On-device tests (optional)

With a device connected or an emulator running:

```bash
./gradlew :app:connectedDebugAndroidTest
```

> This uninstalls the app at the end, which removes its settings and PIN. Use a test device.

---

## Part B: install a ready-made APK (no developer tools)

If someone gives you `app-debug.apk`:

1. Copy it to the phone or tablet, for example by Google Drive, email, a USB cable, or a messenger app as a *document*.
2. Tap the file. Android asks to allow **Install unknown apps** for the app you opened it from (Files, Drive and so on). Allow it, then tap **Install**.
3. If Google Play Protect warns that the app is unknown, choose **Install anyway**. This is expected for test builds that are not from the Play Store.
4. Continue with [Part C](#part-c-first-time-setup-on-the-phone).

Updates install the same way and keep your settings, as long as they were built by the same person on the same computer (same signing key). An APK built by someone else has to replace the app: uninstall first, and your settings are lost.

---

## Part C: first-time setup on the phone

Open **Kids Garden**. It opens as a card over your home screen and walks you through:

1. **Welcome and privacy:** what it does, and that nothing leaves the phone.
2. **Create a parent PIN:** one to six digits. Remember it; there is no recovery (see [Forgot the PIN](#forgot-the-pin)).
3. **Notifications:** allow them, so Android can show the small "Kids Garden is running" notification.
4. **VPN:** tap **Set up VPN** and accept Android's VPN request. Kids Garden runs a VPN **only on the phone**; no traffic goes to any server. Then it checks the phone and shows "This phone can be dulled" or the reason it can't.
5. **Phone calls** (optional): allow call detection so play time pauses during normal phone calls. You can skip this.

You land on **Parent options**:

- **Kids Garden is on / off** switch at the top.
- **Use phone as parent:** normal speed until the screen locks.
- **Give phone to child:** pick 10–120 minutes with the arrows, then **Start play time**.
- **Settings:** dull level, Soft Landing, unlock grace, dulling methods (network, grey screen, lower volume, lower brightness), apps that skip Kids Garden, PIN change.

Optional extra accesses, asked only when you switch the matching effect on in Settings:

- **Grey screen** needs *Display over other apps*.
- **Lower brightness** needs *Modify system settings*.

**Recommended on Samsung, Xiaomi, Huawei and similar phones:** set Kids Garden's battery use to **Unrestricted** (Settings → Apps → Kids Garden → Battery). Otherwise Android may stop it in the background, and the Status screen will say so.

---

## Troubleshooting

| Problem | What to do |
| --- | --- |
| The build fails about missing symlinks or headers in `hev-socks5-tunnel` | Windows: turn on Developer Mode and run the `git submodule foreach … core.symlinks true` command from step A1. Everywhere: make sure the submodule is checked out (`git submodule update --init --recursive`). |
| "NDK not configured" or similar | Install NDK `28.2.13676358` in the SDK Manager (tick *Show Package Details*). |
| Gradle says the Java version is unsupported | Set the Gradle JDK to 21 in Android Studio, or set `JAVA_HOME` to a JDK 21 for command-line builds. |
| Downloads fail with "PKIX path building failed" | Your network inspects TLS (common on company networks). On Windows, adding `systemProp.javax.net.ssl.trustStoreType=Windows-ROOT` to your user `~/.gradle/gradle.properties` usually fixes it. Otherwise import your network's root certificate into the JDK. |
| `INSTALL_FAILED_OLDER_SDK` | The device runs Android 14 or older. Kids Garden needs Android 15 or 16. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed copy was signed by a different computer. Uninstall it first (this removes its settings). |
| `adb devices` shows nothing or "unauthorized" | Check the cable, accept the USB debugging prompt on the phone, or try another USB port or cable. |
| "Another VPN app is using the VPN slot" | Turn off the other VPN (Settings → Network → VPN), then tap **Check this phone** again. Android allows only one VPN at a time. |
| Kids Garden keeps stopping in the background | Set its battery use to Unrestricted (see Part C). |
| The VPN key icon disappeared | The VPN was turned off in Android settings or by another VPN. Open Kids Garden; Status shows what happened and Settings can restart it. |
| Grey screen or brightness doesn't work | Allow *Display over other apps* or *Modify system settings* for Kids Garden; Parent options names the missing access. |

### Forgot the PIN

There is no account and no recovery code, by design. Go to Android **Settings → Apps → Kids Garden → Storage → Clear data** (or uninstall and reinstall), then set Kids Garden up again.

### Uninstall

Settings → Apps → Kids Garden → Uninstall. This removes the app, its VPN and all its settings. Nothing is left on any server, because nothing was ever sent.

---

## Next steps for developers

- The product spec, plan, contracts and task list are in [`specs/001-kids-garden-v1/`](specs/001-kids-garden-v1/). The project principles are in [`.specify/memory/constitution.md`](.specify/memory/constitution.md).
- Code layout: `domain/` holds the pure Kotlin state machine (no Android code, unit-tested) and `app/` the Android app (UI, VPN, settings).
- Kids Garden must not log or collect anything. Please keep it that way: see the [privacy checklist](specs/001-kids-garden-v1/checklists/privacy.md).
- Test scenarios for real devices are in the [quickstart](specs/001-kids-garden-v1/quickstart.md).

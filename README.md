# Kids Garden

**An Android app that makes a child's phone *dull* by default, so screen time feels boring instead of forbidden.**

Kids Garden doesn't block apps or show "time's up" screens. It slows the phone's internet through a local VPN, so videos buffer, feeds load slowly and games lag. A parent unlocks normal speed with a PIN, either for themselves or for a timed play session for the child. When play time ends, the phone eases back to dull over a gentle *Soft Landing*: no alarm, no pop-up, no argument.

> 🤖 **Built with AI.** Kids Garden was specified, designed and coded with AI coding agents — [Claude Code](https://claude.com/claude-code) and [Codex](https://openai.com/codex/) — working from a written spec, under the direction of its human author.
>
> 📖 **Open source** under the [MIT License](LICENSE).

---

## Why

Hard blocks and countdown timers turn screen time into a fight. Children watch the clock, bargain for "five more minutes", and feel punished when the screen goes dark.

Kids Garden takes a different approach:

- **Dull, not blocked.** The phone still works for calls, messages and anything urgent. It just isn't exciting.
- **Quiet endings.** At the end of play time the speed fades down over one or two minutes. There's nothing to fight against and nothing to watch.
- **The parent decides.** Normal speed only ever comes from an explicit parent action, protected by a PIN.
- **Private by design.** Nothing about what the child does is recorded, sent or stored.

## How it works

| Mode | What it means |
|---|---|
| **Dull** (default) | Internet is slowed. Unlocking the phone or opening the app never changes this. |
| **Parent Mode** | Full speed for the parent, with no timer. Locking the phone or turning off the screen ends it. |
| **Play Session** | Full speed for the child for 10–120 minutes, chosen in 10-minute steps. |
| **Soft Landing** | The last minute or two of play, when the speed eases gradually back to dull. |
| **Deactivated** | Kids Garden is switched off in Settings. The phone is never dulled until the parent turns it back on. |

- Enter the PIN, then choose **Use phone as parent** or **Give phone to child**. The PIN is asked again only after the phone locks or after the phone is given to the child, not each time you open the app.
- Play time keeps counting while the phone is locked or offline. It **pauses during normal phone calls**, so a call doesn't eat the child's time.
- The Phone app, WhatsApp and the default SMS app are **excluded from dulling**, so the child can always reach family. The parent can add more **apps that skip Kids Garden** (for example a school app) in Settings.
- A restart, reboot or app update always returns the phone to **Dull**, unless the parent has switched Kids Garden off.
- The parent can switch Kids Garden **off and on** with the switch at the top of Parent options. While off, the VPN is fully stopped and the phone works normally.
- **Unlock grace** (on by default): after an unlock without the PIN, the phone works normally for a few minutes, then slowly gets dull. The grace pauses while the phone is locked, and the next fresh grace comes only after the rest time (default one hour), so the phone just seems "tired". Parent options shows how much grace is left or when the next one comes.
- Besides slowing the internet, Dull Mode can **gently grey the screen, lower media volume and dim the brightness**. Each is switched on or off in Settings, fades in slowly, rests, and repeats. They also work on phones where the VPN can't run.
- During setup Kids Garden **checks whether the phone can be dulled** and says why if it can't.

## Privacy

Kids Garden keeps everything on the device:

- No accounts, servers, analytics or ads.
- No logs, and no history of websites, apps, messages or calls.
- The PIN is stored only as a salted, hardened hash, never as plain text.
- Only the parent's settings are saved (including the start time of the current grace window and the apps the parent lets skip Kids Garden); modes and session times live in memory only.

## Honest limitations

Kids Garden is a gentle nudge, not a lock-down tool. A determined user can switch on another VPN, uninstall the app or reset the phone. It is not a device-owner or MDM solution. The in-app **Limitations** screen lists everything it can't do.

## Status

Version 1 is in active development.

- ✅ Builds locally; domain and app unit tests pass, and lint reports no errors.
- ✅ Dulling, Parent Mode and Play Sessions work on a real phone (Samsung Galaxy A73, Android 16) and on the emulator.
- ⏳ Device qualification is still open: on-device tests, lifecycle and call checks, performance and battery.

## Tech

- Android 15 and 16 (`minSdk 35`, `targetSdk 36`)
- Kotlin and Jetpack Compose
- Jetpack DataStore with a JSON settings document
- Local VPN using [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) (MIT), built with the NDK
- Modules: `:domain` (a pure Kotlin state machine with no Android code) and `:app` (Android)

## Build

**Requirements:** Android Studio with **JDK 21** as the Gradle JDK, Android SDK 36, and NDK `28.2.13676358`.

```bash
git clone --recurse-submodules https://github.com/saebghazal/KidsGarden.git
```

> **Windows:** the tunnel library uses symlinks. Turn on **Developer Mode** first, then run
> `git submodule foreach --recursive "git config core.symlinks true && git checkout -- ."`
> inside `app/src/main/cpp/hev-socks5-tunnel`. The build stops with this hint if the symlinks are missing.

Open the project folder in Android Studio and press **Run**, or from the command line:

```powershell
.\gradlew.bat :domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## Specification

Kids Garden is built spec-first. The product spec, plan, contracts and task list are in [`specs/001-kids-garden-v1/`](specs/001-kids-garden-v1/), and the project principles are in [`.specify/memory/constitution.md`](.specify/memory/constitution.md).

## License

[MIT](LICENSE) © 2026 Saeb M. Ghazal

Third-party code keeps its own license: [hev-socks5-tunnel](app/src/main/cpp/hev-socks5-tunnel/LICENSE) is MIT.

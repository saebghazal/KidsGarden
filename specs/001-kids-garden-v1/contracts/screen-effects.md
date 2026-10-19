# Contract: Screen and Sound Effects

**Status**: Scoped V1 contract for FR-034–036 (added 2026-10-18). Effects are part of Dull Mode, never an authorization mode.

## Domain (pure Kotlin, `:domain`)

| Type | Contract |
| --- | --- |
| `EffectPolicy` | `Off`, or `On(enabled: Set<Effect>, intensity)`. Produced by the reducer: On only when mode is DULL, Kids Garden is Active and no telephony call is ACTIVE. |
| `Effect` | GREY_SCREEN, LOWER_VOLUME, LOWER_BRIGHTNESS |
| `EffectCycle.strength(elapsedMs, buildUpMs, breakMs)` | Returns 0…1. Rises smoothly from 0 to 1 over build-up; eases to 0 within 10 s; stays 0 for break; repeats. Strength 0 in a break means: volume cap lifted, brightness at the saved level, no tint. breakMs = 0 holds at 1 after build-up. Deterministic and unit-tested with fake time. |
| `EffectTargets(intensity)` | Gentle/Medium/Strong: tint alpha 0.40/0.60/0.75; media cap 50/30/15% of max; brightness 60/40/20% of the saved level. |

## Platform (`:app`)

| Component | Contract |
| --- | --- |
| `ScreenEffectsController` | Receives `ApplyEffectPolicy`. Starts a cycle at 0 when the policy is On and the screen is on; stops on ScreenOff; on Off removes all effects within one second and restores brightness. Animates only while the screen is on; no wake locks, alarms or background loops. Ignores stale generations. |
| `GreyOverlay` | One `TYPE_APPLICATION_OVERLAY` view, `FLAG_NOT_TOUCHABLE` and `FLAG_NOT_FOCUSABLE`, alpha ≤ 0.8. No text, no content. Removed during an ACTIVE call. Requires `Settings.canDrawOverlays`. |
| `MediaVolumeCap` | `STREAM_MUSIC` only. Lowers toward the cap with the strength fraction; re-applies the cap when a change raises it during the effect. Never changes call, ring, alarm, notification or accessibility streams; never raises volume itself, in a break or when effects stop; during a break the cap is lifted so the child may raise volume. |
| `BrightnessDimmer` | Saves the parent's level and auto mode once per DULL period via `SettingsRepository.saveBrightnessRestore` before the first change; sets manual mode and lowers brightness with the fraction, so brightness returns to the saved level during each break; restores both and clears the saved value only on Off. Restores a leftover saved value at process start. Requires `Settings.System.canWrite`. |
| `EffectCapabilityProvider` | Reports WORKING / ACCESS_MISSING / UNAVAILABLE per effect to SessionManager; never a history. |

## Privacy and honesty

Effects never read screen content, identify apps, or show the child any message. Nothing about cycles is stored except `brightnessRestore`. The grey screen is a tint, not system greyscale, and does not cover the status bar or system dialogs.

## Tests

`EffectCycleTest` (rise, ease, break, break 0, repeat, restart on screen on). Break: volume cap lifted without raising volume; brightness back at the saved level; saved value kept until Off. Evidence for SC-010. Reducer tests: policy On only in DULL+Active without call; Off on grant, call, Deactivate. Instrumented: overlay passes touches, alpha cap, no overlay during a call; volume cap re-applied and other streams untouched; brightness restored on Off and after process death; access missing disables only that effect. Device: battery with screen off unchanged.

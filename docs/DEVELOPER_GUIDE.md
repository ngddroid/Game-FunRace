# Splash Dash: Developer Guide

## Build and run
```bash
./gradlew :app:assembleDebug          # debug build (mock ads + debug menu + adb automation)
./gradlew :app:assembleRelease        # release build (R8 on, no mock ads); add your signingConfig first
./gradlew :app:testDebugUnitTest      # unit tests (controls, level smoke test, 1-in-17 level sample)
```

**Full level validation** (all 5,000 levels, ~2 minutes). It rewrites `app/src/main/assets/levels/salts.bin`:
```bash
./gradlew :app:testDebugUnitTest --tests "*LevelValidationTest*" -DfullLevelValidation=true
```
Every level must give a precise player a fair timing window at each hurdle group, let the autopilot reach the podium, and let a slow, error-prone player (skill 0.45, `LevelValidator.casualStuck`) get through the whole course within 240 s. Levels that fail are re-rolled. Barrels are spaced so a timed run up the ramp always fits (`LevelBuilder.barrelGap`), and overlapping lazy-river belts are merged into one (`LevelBuilder.mergeBelts`; racers ride the strongest belt, `beltSpeed`).
Re-run it whenever you change hazard timing (`game/sim/Hazards.kt`), racer physics (`game/sim/RaceSim.kt`) or the generator (`level/LevelGenerator.kt`).

**Debug automation** (debug builds only): jump straight into a level, optionally driven by the AI autopilot.
```bash
adb shell am start -n com.ngstudio.funrace/.MainActivity --ei autoLevel 66 --ez autopilot true
```
Note: debug races count as progress, so this unlocks levels on that device. Use Settings → Reset progress to get back to a fresh state.

**Glitch audit.** Run these after any change to hazards, set pieces, racer physics or the way they are drawn:
```bash
./gradlew :app:testDebugUnitTest --tests "*HazardSolidityTest*" --tests "*ClimbFairnessTest*" --tests "*RaceGlitchAuditTest*"
```
- `HazardSolidityTest`: no racer can run through the visible part of any hurdle (stands, posts, hubs, gaps).
- `ClimbFairnessTest`: coconuts hit exactly the climbers they touch; baskets hang clear of climbers.
- `RaceGlitchAuditTest`: races ~40 levels covering every set piece and floor-moving hurdle and checks every racer, every tick. Each racer must stand on the deck, raft or trampoline, hang from a real cable or bar, hug the wall, or sit on the slide surface. Falling or tumbling racers must never be inside a deck slab, a closed flip tile or a tilting deck. Every racer in a race has a unique name.

Drawing and simulation share their sizes through `game/sim/HazardGeometry.kt` (hurdle moving parts) and `game/sim/SetPieceGeometry.kt` (raft, zip cables, monkey bars, trampolines, slide surface, deck holes, tilt and flip-tile surfaces). Change sizes there, not in one side only. The tests can't see render-only mistakes (rope direction, model scale), so also look at a few `autoLevel` races on a phone.

## Controls
Hold anywhere to run, let go to stop. While holding, drag left/right to steer (you can't steer yourself off the edge; only hazards knock you off). Flick up anywhere, or tap the JUMP button, to hop over low hazards (noodle sweepers, rolling barrels, raised bollards, cannon balls, waves).

## Architecture
| Package | What lives there |
|---|---|
| `app/` | `App`, `AppContainer` (manual DI), `Stage` (drives the 3D layer and music) |
| `core/` | Math (`Mat4`, `Quat`), deterministic `Rng`, easing |
| `data/` | `SaveData` (one JSON save), `SaveRepository` (atomic writes), `Catalog` (13 kid racers + 30 Dashlings, 17 hats, 12 trails, 10 dances), `GameRules` (economy, calendar, wheel, missions, achievements, Trophy Road, daily challenge, medals), `Progression` (all save writes from play) |
| `audio/` | `AudioEngine` (SoundPool SFX + gapless streamed music), `Haptics` |
| `ads/` | `AdProvider` interface, debug `MockAdProvider`, release `NoAdProvider`, `AdConfig` |
| `services/` | Firebase and Play: `Telemetry` + `GameEvents` (analytics events, Crashlytics keys), `FirebaseTelemetry`, `Notifications` (channels, posting), `Reminders` + `ReminderWorker` (local come-back reminders), `Push` + `PushService` (FCM), `InAppUpdates` (Play in-app updates) |
| `level/` | `LevelSpec`, `Handcrafted` (1–10), `LevelGenerator` + `DifficultyCurve` (11–5,000), `LevelBuilder`, `LevelValidator`, `LevelRepository`, `Worlds` |
| `game/sim/` | Pure-Kotlin deterministic simulation: `Course`, 20 `Hazards` (incl. Spin Bar, Mega Mallet, Roller Squeeze), set pieces (`Raft`, chasers, storm), `RaceSim`, `AiBrain` |
| `game/render/` | OpenGL ES 3 engine: shaders, `.dmesh` models, GPU-skinned `CharacterRig`, `TrackBuilder`, `HazardVisuals`, `RaceScene`, `MenuScene`, water/sky/particles |
| `game/RaceController` | Joins simulation, input, HUD state, sound/haptics and results |
| `ui/` | Jetpack Compose: theme, components, screens (splash, onboarding, home, levels, shop, events, profile, settings, legal, race, result) |

**Launch sequence** (cold start). Each step is gated by `LaunchCurtain`:
1. The system launch screen: plain white, with no icon (`drawable/splash_blank.xml`). It is removed as soon as the game draws (`LaunchCurtain.systemGone()`).
2. The NG Studio intro (`StudioIntroScreen`, 3.9 s, tap to skip after 1.2 s). It is the same ident as Card Mania, with the logo layers in `drawable-nodpi/ngi_*.png`. It waits for step 1 to finish.
3. The game splash: Kai drops onto the stage and the logo lands (`LaunchCurtain.lift()`, 0.4 s after the intro's white has faded).

The menu scene renders behind steps 1–2, with Kai waiting up in the sky, so the landing starts warm. A recreated activity, or a debug `autoLevel` launch, skips the intro.

The 3D `GLSurfaceView` sits behind a transparent Compose layer; one GL context serves every screen. Screens without 3D (Levels, Events, Settings, Legal) pause rendering to save battery.

## Regenerating assets
```bash
B=~/Applications/Blender.app/Contents/MacOS/Blender
$B -b --factory-startup --python tools/blender/build_assets.py              # all 149 models -> assets/models/*.dmesh
$B -b --factory-startup --python tools/blender/build_assets.py -- hazards --only duck,noodle
$B -b --factory-startup --python tools/blender/build_assets.py -- kids            # kid body, hairstyles, backpack
$B -b --factory-startup --python tools/blender/preview_avatars.py -- /tmp/avatars.png spec.json   # coloured avatar sheet
$B -b --factory-startup --python tools/blender/preview_dmesh.py -- /tmp/sheet.png dashling duck   # contact sheet
$B -b --factory-startup --python tools/blender/render_icon.py -- /tmp/art all  # icon + hero renders
python3 tools/make_icons.py /tmp/art                                            # launcher icons, splash, store art
$B -b --factory-startup --python tools/blender/preview_avatars.py -- /tmp/art/splash_avatar.png tools/blender/splash_avatar.json portrait
python3 tools/make_icons.py --splash /tmp/art/splash_avatar.png                 # Kai hopping (8 frames + splash_icon_anim.xml); no longer on the launch screen, kept in tools/blender/splash_hop/
python3 tools/gen_ui_art.py                                                     # 60 UI icons
python3 tools/gen_audio.py                                                      # 53 SFX + 5 music loops
```
All generators are deterministic and need no downloads.

## Firebase and Google Play services
Firebase project `games-1d48d`; its config is `app/google-services.json`. The Firebase BoM is held at 34.x because 35.x is built with Kotlin 2.4 and the project uses Kotlin 2.2 (move both together).

**Analytics.** Game code reports through `GameEvents` (pure Kotlin, no Firebase imports, so tests and tools stay JVM-only). `App.onCreate` installs `FirebaseTelemetry` as the sink. Events: `screen_view`, `level_start`, `level_end` (success, rank, time, stars, bonks), `tutorial_begin` / `tutorial_complete`, `earn_virtual_currency` / `spend_virtual_currency`, `unlock_achievement`, `booster_earned`, `item_earned`, `daily_challenge_cleared`, `time_trial_medal`, `level_unlock_ad`, `reminder_shown`, `notification_tap`, `notif_permission`, `update_prompt`. User properties: `highest_level`, `character`. The advertising ID and ad personalisation are switched off in the manifest. To watch events live in the console's DebugView:
```
adb shell setprop debug.firebase.analytics.app com.ngstudio.funrace
```

**Crashlytics.** Crashes, ANRs and `Telemetry.nonFatal(t)` reports, with the keys `screen`, `level`, `quality` and `gpu_tier` and the last events as breadcrumbs. Release builds upload the R8 mapping file automatically. Debug builds have **Settings → Debug → Test crash**; reopen the game afterwards so the report is sent.

Settings → **Usage & crash reports** turns both off (`Settings.shareData`).

**Local reminders.** When the game goes to the background, `Reminders.schedule` books three WorkManager jobs, 1, 3 and 7 days later, at the time of day the player was playing (moved into 10:00–20:30). Opening the game cancels them. The text depends on the save (daily gift ready, free spin, current level). Debug: **Test notification now**, or **Reminders in 10 s**, then leave the game.

**Push (FCM).** Every install subscribes to the topic `all`, and debug builds also to `debug`. Debug builds log the token (`adb logcat -s Push`). Send from Firebase console → Messaging; target the topic `debug` (or the logged token) to test on your own phones. While the game is open, `PushService` posts the message itself. While it is closed, Android shows it with the `ic_stat_splash` icon on the "News & events" channel. A data message with `title`, `body` and optional `channel` (`news` or `reminders`) is shown in both cases. Turning **Notifications** off in Settings unsubscribes and deletes the token.

**Notification permission (Android 13+).** After the player's second race, the main menu shows a friendly ask, then Android's dialog, once. Settings → **Notifications** asks again. If Android won't show its dialog any more, the switch opens the system notification settings.

**In-app updates.** Checked once per launch on the main menu, never in a race. Update priority 4–5 (set through the Play Developer API) triggers an immediate update; anything else shows Play's flexible prompt once the update is a day old, at most every 3 days, then the "Update Ready! Restart" dialog. Only Play-installed builds can test this: use internal app sharing or the internal testing track and upload a higher `versionCode`. A sideloaded build just logs `InAppUpdates: no Play update check`.

## Adding real ads later
1. Add your ad SDK and implement `AdProvider` (`showRewarded`).
2. Return it from `AdConfig.create()` for release, and set `AdConfig.rewardedUnlockEnabled = true` if locked levels should be unlockable by ads in release.
3. Remove the `AD_ID` / `ACCESS_ADSERVICES_*` permission removals and the two `google_analytics_*` meta-data in `AndroidManifest.xml` if the ads need the advertising ID, update `assets/legal/privacy.html`, and update the Play Console Data safety form.
4. Keep the fair-play rule: never show ads during a race.

## Release checklist
- Legal pages: the in-app `app/src/main/assets/legal/*.html` and the website `store/website/public/` (zipped as `store/website/public.zip`) carry NGENDEV TECHNOLABS LLP's details, effective November 25, 2026. Change both together, and update the effective date when either changes. The Play Console privacy policy URL is the hosted `privacy_policy.html`.
- Add a release `signingConfig` in `app/build.gradle.kts`.
- Play Console: content rating (cartoon slapstick, no violence), target audience 13+ recommended until you add Families-certified ads.
- Play Console **Data safety**: collected and shared with Google as a processor: *App activity* (app interactions, in-game actions), *App info and performance* (crash logs, diagnostics), *Device or other IDs* (Firebase app-instance ID, FCM token). Purpose: analytics, app functionality. Not used for ads. Encrypted in transit. Users can turn it off in Settings. The advertising ID is **not** collected (answer "No" to the advertising ID question).
- Bump `versionCode` for every upload; in-app updates compare it.
- Store art: `store/play_icon_512.png`, `store/feature_graphic_1024x500.png`.
- Run the full level validation and `./gradlew :app:assembleRelease`.

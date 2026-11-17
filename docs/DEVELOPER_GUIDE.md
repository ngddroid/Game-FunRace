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
Re-run it whenever you change hazard timing (`game/sim/Hazards.kt`), racer physics (`game/sim/RaceSim.kt`) or the generator (`level/LevelGenerator.kt`).

**Debug automation** (debug builds only): jump straight into a level, optionally driven by the AI autopilot.
```bash
adb shell am start -n com.ngstudio.funrace/.MainActivity --ei autoLevel 66 --ez autopilot true
```
Note: debug races count as progress, so this unlocks levels on that device. Use Settings → Reset progress to get back to a fresh state.

## Controls
Hold anywhere to run, let go to stop. While holding, drag left/right to steer (you can't steer yourself off the edge; only hazards knock you off). Flick up anywhere, or tap the JUMP button, to hop over low hazards (noodle sweepers, rolling barrels, raised bollards, cannon balls, waves).

## Architecture
| Package | What lives there |
|---|---|
| `app/` | `App`, `AppContainer` (manual DI), `Stage` (drives the 3D layer and music) |
| `core/` | Math (`Mat4`, `Quat`), deterministic `Rng`, easing |
| `data/` | `SaveData` (one JSON save), `SaveRepository` (atomic writes), `Catalog` (30 Dashlings, 17 hats, 12 trails, 10 dances), `GameRules` (economy, calendar, wheel, missions, achievements, Trophy Road, daily challenge, medals), `Progression` (all save writes from play) |
| `audio/` | `AudioEngine` (SoundPool SFX + gapless streamed music), `Haptics` |
| `ads/` | `AdProvider` interface, debug `MockAdProvider`, release `NoAdProvider`, `AdConfig` |
| `level/` | `LevelSpec`, `Handcrafted` (1–10), `LevelGenerator` + `DifficultyCurve` (11–5,000), `LevelBuilder`, `LevelValidator`, `LevelRepository`, `Worlds` |
| `game/sim/` | Pure-Kotlin deterministic simulation: `Course`, 17 `Hazards`, set pieces (`Raft`, chasers, storm), `RaceSim`, `AiBrain` |
| `game/render/` | OpenGL ES 3 engine: shaders, `.dmesh` models, GPU-skinned `CharacterRig`, `TrackBuilder`, `HazardVisuals`, `RaceScene`, `MenuScene`, water/sky/particles |
| `game/RaceController` | Joins simulation, input, HUD state, sound/haptics and results |
| `ui/` | Jetpack Compose: theme, components, screens (splash, onboarding, home, levels, shop, events, profile, settings, legal, race, result) |

The 3D `GLSurfaceView` sits behind a transparent Compose layer; one GL context serves every screen. Screens without 3D (Levels, Events, Settings, Legal) pause rendering to save battery.

## Regenerating assets
```bash
B=~/Applications/Blender.app/Contents/MacOS/Blender
$B -b --factory-startup --python tools/blender/build_assets.py              # all 149 models -> assets/models/*.dmesh
$B -b --factory-startup --python tools/blender/build_assets.py -- hazards --only duck,noodle
$B -b --factory-startup --python tools/blender/preview_dmesh.py -- /tmp/sheet.png dashling duck   # contact sheet
$B -b --factory-startup --python tools/blender/render_icon.py -- /tmp/art all  # icon + hero renders
python3 tools/make_icons.py /tmp/art                                            # launcher icons, splash, store art
python3 tools/gen_ui_art.py                                                     # 60 UI icons
python3 tools/gen_audio.py                                                      # 53 SFX + 5 music loops
```
All generators are deterministic and need no downloads.

## Adding real ads later
1. Add your ad SDK and implement `AdProvider` (`showRewarded`).
2. Return it from `AdConfig.create()` for release, and set `AdConfig.rewardedUnlockEnabled = true` if locked levels should be unlockable by ads in release.
3. Add the `INTERNET` permission, update `assets/legal/privacy.html`, and update the Play Console Data safety form.
4. Keep the fair-play rule: never show ads during a race.

## Release checklist
- Replace `[STUDIO NAME]`, `[CONTACT EMAIL]`, `[EFFECTIVE DATE]`, `[COUNTRY/STATE]` in `app/src/main/assets/legal/*.html`.
- Add a release `signingConfig` in `app/build.gradle.kts`.
- Play Console: content rating (cartoon slapstick, no violence), target audience 13+ recommended until you add Families-certified ads, Data safety (no data collected in this version).
- Store art: `store/play_icon_512.png`, `store/feature_graphic_1024x500.png`.
- Run the full level validation and `./gradlew :app:assembleRelease`.

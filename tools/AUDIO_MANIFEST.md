# Splash Dash: Obstacle Party - audio manifest

All audio is 100% original and procedurally synthesised by `tools/gen_audio.py`
(Python 3 standard library only, fixed seeds, no samples, no downloads, bit-identical on
every run). Regenerate everything from the project root with:

    python3 tools/gen_audio.py

Format: WAV (plain RIFF with only `fmt ` + `data` chunks), PCM 16-bit, mono, 22050 Hz, in
`app/src/main/res/raw/`.

- One-shot SFX: silence trimmed, raised-cosine fade-in (>= 3 ms) and fade-out (>= 5 ms, longer
  on ringing tails), peak-normalised to -1 dBFS. They start and end on exact zero.
- Loops (`sfx_*_loop`, `music_*`) are rendered circularly: note releases, echoes, reverb and
  filter tails that ring past the loop end are folded back onto the start, so they repeat
  seamlessly (`MediaPlayer.isLooping = true` / SoundPool `loop = -1` / custom streamer).
- Music is mastered to about -15 LUFS integrated (BS.1770-style K-weighted, gated) with a
  circular look-ahead limiter at -3 dBFS, so it sits under the louder, punchier SFX.
  Relative balance between sounds can still be tuned in-game via playback volume.

| File | Type | Duration (s) | Size (KB) | Peak (dBFS) | Level | Description |
|---|---|---:|---:|---:|---|---|
| `sfx_ui_tap.wav` | one-shot | 0.075 | 3.3 | -1.0 | -14.2 rms | Soft bubbly pop tap: rising sine bubble + tiny click. |
| `sfx_ui_back.wav` | one-shot | 0.090 | 3.9 | -1.0 | -13.6 rms | Lower, rounder bubble pop for back/cancel. |
| `sfx_ui_open.wav` | one-shot | 0.291 | 12.6 | -1.0 | -14.0 rms | Panel open: airy upward swoosh into a rising "bloop". |
| `sfx_ui_close.wav` | one-shot | 0.253 | 11.0 | -1.0 | -14.7 rms | Panel close: downward swoosh into a falling "bloop". |
| `sfx_coin.wav` | one-shot | 0.336 | 14.5 | -1.0 | -13.1 rms | Bright E6-B6 coin "bling" with metallic ring and sparkles. |
| `sfx_coin_tick.wav` | one-shot | 0.040 | 1.8 | -1.0 | -13.7 rms | Tiny high E7 tick for coin count-up. |
| `sfx_star1.wav` | one-shot | 0.500 | 21.6 | -1.0 | -13.8 rms | Star 1: celesta chime G5->C6 with warm pan body and twinkles. |
| `sfx_star2.wav` | one-shot | 0.620 | 26.7 | -1.0 | -14.0 rms | Star 2: brighter chime C6->E6, more sparkle. |
| `sfx_star3.wav` | one-shot | 0.900 | 38.8 | -1.0 | -16.5 rms | Star 3 (biggest): E6-G6-C7 chime run, G7 sparkle and shimmer tail. |
| `sfx_unlock.wav` | one-shot | 1.100 | 47.4 | -1.0 | -15.2 rms | Magical unlock: C5-E5-G5-C6 steel-pan run, bell chord, sparkle tail. |
| `sfx_chest_open.wav` | one-shot | 1.427 | 61.5 | -1.0 | -16.8 rms | Hinge creak (stick-slip), lid thunk, then a bell/pan sparkle burst with magic dust. |
| `sfx_wheel_tick.wav` | one-shot | 0.030 | 1.3 | -1.0 | -16.4 rms | Short wooden click for each prize-wheel peg. |
| `sfx_wheel_win.wav` | one-shot | 1.150 | 49.6 | -1.0 | -15.2 rms | Short brass + steel-pan fanfare (C-F-C) with timpani hit and bells. |
| `sfx_countdown.wav` | one-shot | 0.170 | 7.4 | -1.0 | -6.3 rms | Friendly A5 beep with a woody marimba onset for 3-2-1. |
| `sfx_go.wav` | one-shot | 1.008 | 43.4 | -1.0 | -17.6 rms | Big start: A6 beep + A-major horn hit + full water SPLASH and whoosh. |
| `sfx_step_vinyl1.wav` | one-shot | 0.132 | 5.7 | -1.0 | -13.4 rms | Inflatable footstep: soft vinyl "thup" with a short rubbery squeak (up). |
| `sfx_step_vinyl2.wav` | one-shot | 0.130 | 5.7 | -1.0 | -13.9 rms | Inflatable footstep variation: slightly higher thup, squeak bends down. |
| `sfx_step_wood.wav` | one-shot | 0.100 | 4.3 | -1.0 | -15.3 rms | Wooden plank footstep (modal knock + low thud). |
| `sfx_step_stone.wav` | one-shot | 0.098 | 4.3 | -1.0 | -16.5 rms | Stone footstep: gritty scuff + dull thud. |
| `sfx_step_ice.wav` | one-shot | 0.090 | 3.9 | -1.0 | -16.4 rms | Ice footstep: crisp crunch grains with a tiny icy tink. |
| `sfx_bonk.wav` | one-shot | 0.418 | 18.0 | -1.0 | -14.2 rms | Rubbery cartoon hit: noise "thwap" + thud + wobbling pitch-drop boing. |
| `sfx_squeak.wav` | one-shot | 0.320 | 13.8 | -1.0 | -9.8 rms | Inflatable toy squeak: reedy tone bending up and back with air. |
| `sfx_boing.wav` | one-shot | 0.593 | 25.6 | -1.0 | -16.9 rms | Jump-pad spring "boyoyoing": wobbling pitch + jaw-harp formant sweep. |
| `sfx_trampoline.wav` | one-shot | 0.850 | 36.6 | -1.0 | -13.5 rms | Deeper bouncy trampoline: membrane whomp + slow low boing. |
| `sfx_zip_loop.wav` | loop | 1.500 | 64.6 | -1.0 | -8.8 rms | Zipline LOOP: trolley-wheel whine, metallic cable zing, hiss, rumble and rattles. |
| `sfx_slide_loop.wav` | loop | 2.000 | 86.2 | -1.0 | -11.8 rms | Water-slide LOOP: rushing water with swells, spray hiss and a stream of bubbles. |
| `sfx_rope_creak.wav` | one-shot | 0.724 | 31.2 | -1.0 | -21.0 rms | Rope creak: two stick-slip creaks through rope/post resonances. |
| `sfx_climb.wav` | one-shot | 0.137 | 5.9 | -1.0 | -19.5 rms | Hand grab on plastic grip: quick swish, hollow plastic tap, tiny rubber squeak. |
| `sfx_splash_big.wav` | one-shot | 1.051 | 45.3 | -1.0 | -17.1 rms | Body falls into water: slap, plunge thump, spray, bubbles, glugs and drips. |
| `sfx_splash_small.wav` | one-shot | 0.450 | 19.4 | -1.0 | -18.3 rms | Small splash: light slap, short spray, a few bubbles and droplets. |
| `sfx_wind_gust.wav` | one-shot | 1.235 | 53.2 | -1.0 | -15.5 rms | Fan gust: swelling band-passed wind with low body, whistle and blade flutter. |
| `sfx_wave_surge.wav` | one-shot | 1.222 | 52.7 | -1.0 | -18.3 rms | Wave surge: rising rumble to a crest, white-water wash and fizz. |
| `sfx_cannon_pop.wav` | one-shot | 0.348 | 15.0 | -1.0 | -13.2 rms | Beach-ball cannon "foomp": air burst, low thump and tube pop. |
| `sfx_glove_punch.wav` | one-shot | 0.487 | 21.0 | -1.0 | -14.9 rms | Spring-loaded boxing glove: sproing + swish + padded leather impact. |
| `sfx_press_thump.wav` | one-shot | 0.592 | 25.6 | -1.0 | -12.5 rms | Heavy soft press: deep padded thump with an air squish. |
| `sfx_trapdoor.wav` | one-shot | 0.200 | 8.7 | -1.0 | -18.9 rms | Plastic trapdoor clack-clack with a small rattle. |
| `sfx_gate.wav` | one-shot | 0.711 | 30.7 | -1.0 | -18.3 rms | Sliding gate: whoosh-roll then a solid clunk. |
| `sfx_paddle.wav` | one-shot | 0.800 | 34.5 | -1.0 | -17.6 rms | Water-wheel paddle: slap into water, slosh, pour-off bubbles, axle creak. |
| `sfx_checkpoint.wav` | one-shot | 0.950 | 41.0 | -1.0 | -15.4 rms | Checkpoint: bright G5-C6-E6 pan/bell chime with a flag flutter. |
| `sfx_nice.wav` | one-shot | 0.600 | 25.9 | -1.0 | -13.4 rms | Short positive stinger: steel pan A5 -> D6+F#6 with marimba. |
| `sfx_perfect.wav` | one-shot | 1.150 | 49.6 | -1.0 | -14.3 rms | Bigger sparkly stinger: pan run into C-major chord, glock sparkles, shimmer. |
| `sfx_turbo.wav` | one-shot | 0.900 | 38.8 | -1.0 | -14.7 rms | Turbo: rocket jet sweep with rising tone, crackle and ignition thump. |
| `sfx_whoosh.wav` | one-shot | 0.317 | 13.7 | -1.0 | -14.7 rms | Quick swish (band-pass sweep up then down). |
| `sfx_pop.wav` | one-shot | 0.080 | 3.5 | -1.0 | -14.5 rms | Bubble pop. |
| `sfx_powerup.wav` | one-shot | 0.570 | 24.6 | -1.0 | -13.0 rms | Booster activation: rising G-major arpeggio over a charging sweep, sparkle. |
| `sfx_shield_pop.wav` | one-shot | 0.388 | 16.7 | -1.0 | -21.2 rms | Soap-bubble shield bursting: film snap, soft fwup, falling shimmer, droplets. |
| `sfx_cheer.wav` | one-shot | 2.032 | 87.6 | -1.0 | -20.0 rms | Crowd cheer (noise-shaped "yaaay" formants, individual yells, applause, whistles). |
| `sfx_win_fanfare.wav` | one-shot | 2.600 | 112.0 | -1.0 | -14.9 rms | Happy fanfare: steel-pan pickup, brass F-G-C hits, timpani, crash, pan roll. |
| `sfx_lose.wav` | one-shot | 1.416 | 61.0 | -1.0 | -9.9 rms | Gentle "aww": muted-trombone E4-D4-C4 with droop and wobble. |
| `sfx_error.wav` | one-shot | 0.244 | 10.5 | -1.0 | -7.3 rms | Soft low double buzz. |
| `sfx_tide_loop.wav` | loop | 2.000 | 86.2 | -1.0 | -12.7 rms | Lagoon tide LOOP: low rolling water rumble, slow swell, glugs and fizz. |
| `sfx_duck_quack.wav` | one-shot | 0.497 | 21.4 | -1.0 | -10.8 rms | Giant rubber-duck honk-squeak (raspy reed through nasal formants). |
| `sfx_thunder.wav` | one-shot | 2.128 | 91.7 | -1.0 | -14.9 rms | Soft cartoon thunder: muffled crack and rolling low rumble. |
| `music_menu.wav` | music loop | 28.800 | 1240.4 | -3.0 | -15.0 LUFS | Sunny calypso menu theme, F major, 100 bpm, 12 bars: steel-pan lead, marimba comping, soft pad, round bass, shaker, bongos, clave. |
| `music_race.wav` | music loop | 28.444 | 1225.0 | -3.0 | -15.0 LUFS | Upbeat soca-pop race theme, C major, 135 bpm, 16 bars: steel-pan hook, ukulele strums, xylophone lift, synth bass, kick/snare/claps, bongos. |
| `music_race_night.wav` | music loop | 29.091 | 1252.9 | -3.0 | -15.0 LUFS | Neon night race, A minor, 132 bpm, 16 bars: synth-pluck hook with delay, 16th arps, pumping pad, octave bass, claps, risers. |
| `music_boss.wav` | music loop | 25.600 | 1102.5 | -3.0 | -15.0 LUFS | Tense-but-fun boss chase, D minor, 150 bpm, 16 bars: staccato string ostinato, brassy lead, galloping bass, toms, timpani, xylophone. |
| `music_bonus.wav` | music loop | 26.880 | 1157.7 | -3.0 | -15.0 LUFS | Carefree coin rush, G major, 125 bpm, 14 bars (A, A2, B + 2-bar turnaround): whistle lead, xylophone skank, bouncy bass, claps, tambourine, coin sparkles. |

Music loop lengths are sample-exact whole bars (menu 28.800 s, race 28.444 s, race_night 29.091 s, boss 25.600 s, bonus 26.880 s).

Total: 58 files, 7.80 MB (7802336 bytes) in res/raw (budget 8 MB).

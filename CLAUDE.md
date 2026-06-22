# Action Replay — Claude working notes

## Hard constraint: dev/test split
- **This Mac (`m_vincent@`)**: development only. Network is restricted — assume **no** ability to fetch new artifacts, run Gradle sync against remote repos, install emulator system images, or hit Google Maven. Treat dependency additions and version bumps as costly: prefer what's already resolvable, and call out anything that would require a fresh download.
- **User's personal computer**: where `./gradlew assembleDebug`, `installDebug`, and emulator runs happen. All real verification lives there.
- Practical implication for Claude: **don't try to build, lint, or run unit tests from here**. Don't propose commands that need network. Ship code that is internally consistent and let the user run it on the other machine; if a Gradle/Kotlin/AGP version mismatch shows up there, fix by editing files, not by chasing it locally.

## Product spec (locked in 2026-06-19 conversation)
- Landscape Android app, back camera. **`sensorLandscape` (2026-06-22):** rotates between both landscape orientations (180° flip) following the physical sensor, even with system auto-rotate off. Portrait is never used. Orientation is **locked to the current landscape while recording** (Play→Stop) so a mid-record flip can't produce a half-upside-down clip (MediaMuxer's orientation hint is fixed at file creation). Preview is counter-rotated 180° in reverse-landscape; saved-file `orientationHint` is computed from live display rotation.
- Layout: left **3/4** width = live preview, right **1/4** = vertical column of buttons.
- Controls (top → bottom), **redesigned 2026-06-22** — replaced the old four equal Play/Stop/Rewind buttons (two lookalike-but-different buttons confused users): a single **record toggle**, a hairline divider, then **Replay 5s** / **Replay 10s**.
  - **Record toggle**: shows **LAUNCH** when idle, **END** when recording (label/colour/icon flip on `isRecording`). One button whose label always states the next action — there's no separate Stop to misread as pausable.
  - **LAUNCH** runs a "3, 2, 1, GO SHOOT!" countdown, then arms recording **on GO** (capture starts at the launch beat, not the tap). The toggle is disabled through the whole countdown incl. the GO SHOOT hold.
  - **END** ends the session and saves the full LAUNCH→END span as one MP4. **Disabled while an inline replay is playing** (a reflex tap can't cut the battle short) and during the countdown. If END is reached before the first keyframe is muxed, the session is **discarded** (no corrupt file published) — see Known simplifications.
- Engine entry points are still `beginSession()` / `endSession()`; only the UI controls and their gating changed.
- **Rolling buffer runs continuously from app launch** (≥5s of encoded video+audio held in memory). **Cleared on LAUNCH (2026-06-22):** rewind is session-scoped — it only ever pulls footage captured after LAUNCH, never pre-launch frames. The replay buttons re-earn their **≥5s / ≥10s** threshold from the moment LAUNCH is pressed.
- **Replay 5s / Replay 10s**: snapshot last 5s/10s from the rolling buffer, save as MP4, then **play it back inline in the preview area** (at 0.5× slow-mo; saved file stays normal speed), then return to live camera. (Durations bumped from 3s/5s on 2026-06-22.)
- Replay buttons are **disabled until the buffer holds ≥5s / ≥10s**, and while a replay is already playing.
- Audio is captured with video.
- Output goes to the **shared gallery** via MediaStore under `Movies/ActionReplay/`.

## Visual design — "Beyblade X" (2026-06-22)
The app is for filming **Beyblade X** battles and replaying the finish; the UI is themed to the franchise. (Earlier passes — a cyan/purple synthwave HUD, then an amber "LSM Deck" broadcast look — are both superseded; don't reintroduce amber/purple.)
- **Palette** — shared `internal val`s in `ui/Type.kt` (hoisted there once a third screen needed them; previously duplicated per-file): ink `BgTop #0A1018`/`BgBottom #05080F`, `XBlue #0091FF`, `Cyan #29E0FF`, `StrikeRed #FF2D2D`, `Volt #FFE600`, `Steel #5C6B7E`, `Panel #101A28`, `Divider #1E2C3E`. Cyan-on-ink is grounded in the brand here, **not** a synthwave default. All screens reference these names directly (same package).
- **Type**: VT323 (pixel/terminal, SIL OFL) via `ui/Type.kt` → `BattleFont`. **Requires `app/src/main/res/font/vt323.ttf`** — can't be fetched from the dev Mac (no network), and the build fails on `R.font.vt323` until the file is present on the build machine. Used for all HUD/display text; running prose (permission-screen body copy) stays `Monospace` for readability. VT323 has **no italic** — styles using `BattleFont` must not set `fontStyle = Italic` (faux-skewed pixel type looks bad). Keep `BattleFont` text **ASCII-safe** (e.g. `> REPLAY 0.5x`, not `▶ … ×`) so a missing glyph doesn't silently fall back to a system font.
- **Signature — spin gauges**: the Replay buttons render an RPM tick ring that winds up as the buffer fills toward threshold; once ready, a cyan spark orbits the rim (a top still spinning). `SpinGauge` composable, only composed for `dial` buttons (so the record toggle never runs the infinite transition).
- **GO SHOOT! countdown** on LAUNCH (`CountdownOverlay`) and an **X-slash wipe** (blue X grows from centre) that replaces dip-to-black on replay entry — the blue fill still fully covers the SurfaceView swap at peak, so the cover guarantee is preserved.
- Recording tally reads **● BATTLE**; the during-playback bug reads **`> REPLAY 0.5x`** for a rewind or **`> REWATCH`** for a reel rewatch (normal speed).
- **Feedback is a branded in-app status bug, not system Toasts** (`StatusMsg` + `StatusBug`, bottom-centre, auto-dismiss ~1.6s): `REPLAY SAVED` / `BATTLE SAVED` / `BATTLE NOT SAVED` / `CLIP UNAVAILABLE`, accent cyan (ok) or strike-red (error). Errors are directive, never developer-leak (the old "Saved rewind5s" Toast is gone). Don't reintroduce `Toast`.
- **Reduced motion respected:** `isReducedMotion()` (in `ui/Type.kt`, reads `ANIMATOR_DURATION_SCALE`, cached via `remember`) pins the spin-gauge spark static and holds the STANDBY pulse steady when the user has animations off; functional motion (countdown, X-slash, gauge fill) stays.

## Stack
- Kotlin + Jetpack Compose UI.
- **Camera2 + MediaCodec** (not CameraX) — CameraX Recorder doesn't expose encoded frames for a rolling buffer.
- minSdk 29 (Android 10), targetSdk current.
- Inline playback uses `android.widget.VideoView` (no extra dep).

## Architecture
- `camera/CameraEngine.kt` — owns Camera2 session, video MediaCodec (input surface), audio MediaCodec, and a `SampleRingBuffer` of encoded samples keyed by PTS. Also owns the live-session `MediaMuxer` when Play is active.
- `camera/SampleRingBuffer.kt` — bounded deque of encoded samples; clip extraction starts from the latest video keyframe at-or-before `(now − N seconds)`.
- `camera/ClipSaver.kt` — MediaStore insert helper.
- `ui/CameraScreen.kt` — Compose layout (preview + record toggle + 2 replay spin-gauge buttons + countdown/replay overlays + X-slash transition + reel chip/modal). **Holds the UI state directly via `remember`** — `isRecording`, `rewindBusy`, `countdown`, `bufferedSec`, `requestedUri`/`displayedUri`, `status`, `savedClips`, `reelOpen`, `slowMo` — there is **no separate ViewModel**. Talks to the engine through a `CameraEngine.Listener`.
- `ui/PermissionScreen.kt` — pre-grant "STANDBY" screen: camera/mic shown as VIDEO/AUDIO signal channels (READY / NO SIGNAL); primary button flips "Enable camera & mic" → "Open settings" on permanent denial.
- `ui/ReelOverlay.kt` — the "payoff loop": `SavedClip`/`ClipKind` model, `ReelChip` opener, and `ReelOverlay` modal (a `LazyRow` of thumbnail cards — `loadThumbnail`, no dep — with rewatch + delete). See Known simplifications for the session-only/idle-only design.
- `ui/Type.kt` — shared UI primitives: the palette (`internal val`s), `BattleFont` (VT323), and `isReducedMotion()`.
- `MainActivity.kt` — ComponentActivity; **permission state machine**: separate camera/mic tracking, re-requestable, permanent-denial detection (`shouldShowRequestPermissionRationale` gated on a persisted `hasAsked`), Settings route, `onResume` re-check, auto-ask only on first creation (`savedInstanceState == null`), `hasAsked` persisted across recreation. Hosts `CameraScreen` when both granted, else `PermissionScreen`.

## Known simplifications / `ponytail:` markers
- No foreground service: backgrounding the app stops capture. Add a `MediaProjection`-style foreground service later if "must keep recording while user switches apps" becomes a requirement.
- **Resolution is device-best (2026-06-22):** `selectCaptureConfig()` picks the largest 16:9 encoder size the back camera supports, **capped at 1920×1080** (4K skipped for encoder/rolling-buffer load), and scales bitrate via `VIDEO_BITS_PER_PIXEL` (≈5 Mbps at 720p30). **FPS stays 30** — >30 needs a constrained high-speed capture session (not implemented). The chosen `{height}p · {fps}FPS` is reported to the UI via `Listener.onCaptureConfig` and shown in the footer. GOP=1s, H.264, AAC 128 kbps. The `VIDEO_W/H/FPS/BITRATE` consts are now fallbacks.
- Ring buffer holds 13s (over the 10s max rewind) so the rewind-10s extraction can always anchor on a prior keyframe.
- Inline playback uses `VideoView` rather than Media3/ExoPlayer to avoid pulling in a large dep tree.
- **GO SHOOT countdown defers session start (2026-06-22):** `beginSession()` fires ~2.5s after the LAUNCH tap (on GO), inside a coroutine on `rememberCoroutineScope`. `beginSession()` returns `Boolean` and is a **no-op returning false when the engine isn't running** (app backgrounded mid-countdown → `surfaceDestroyed` → `stop()`), so it can't build a `MediaMuxer` on a dead engine. The coroutine aborts cleanly on `false`; a `finally` always clears `countdown` (so LAUNCH can't stick disabled) and restores `SENSOR_LANDSCAPE` if the launch aborted.
- **Replay watchdog (2026-06-22):** END's enablement depends on `!rewindBusy`, so `LaunchedEffect(rewindBusy)` force-returns to live after `REWIND_WATCHDOG_MS` (30s, comfortably over the longest 10s@0.5× ≈ 21s replay) in case a `VideoView` never reports completion on some OEM — otherwise END could stay disabled for the whole session. ponytail: last-resort cap with a known ceiling; replace with a real per-clip completion signal if it ever proves too coarse.
- **`endSession()` discards an unstarted muxer (2026-06-22):** ending before the first keyframe (`liveStarted == false`) deletes the cache file and reports `onSessionSaved(null)` instead of publishing a header-only/corrupt MP4. `finalizeLiveMuxerLocked(publish=false)` leans on the `try/catch` swallowing the `IllegalStateException` from `MediaMuxer.stop()` on a never-started muxer.
- **Reel is session-scoped + idle-only (2026-06-22):** `savedClips` is an in-memory `mutableStateListOf` appended on each successful save (REPLAY on `onClipSaved`, BATTLE on `onSessionSaved`) — **lost on process death** (consistent with the rolling buffer) and uses **no MediaStore query / no new permission** (reuses the `Uri`s the engine already returns). The reel chip/modal only appear when **idle** (`!isRecording && countdown==null && displayedUri==null`), so rewatch audio can't bleed into a live mic. ponytail upgrades: query MediaStore for persistence (needs `READ_MEDIA_VIDEO` on API 33+ — a new permission); mute-during-record for mid-battle browsing.
- **Rewatch reuses the inline player at normal speed (2026-06-22):** tapping a reel card sets `slowMo=false` then `requestedUri=clip.uri`, reusing the existing transition/skip path. `slowMo` (default true → 0.5× for rewinds) is read in the VideoView `onPrepared` at fire time. Rewatch does **not** set `rewindBusy`, so the 30s replay watchdog can't cut a long BATTLE rewatch; the always-available BACK-TO-LIVE/tap-skip is the exit. The `playing` flag passed to `ActionReplayPanel` (`requestedUri != null || displayedUri != null`) disables LAUNCH for the whole playback incl. the dip-in. **Delete** uses `contentResolver.delete` on the owned item (permission-free on API 29+), off-thread, behind a confirm.
- **Compose gotcha (learned the hard way):** a full-screen overlay with only `background()` does **not** block touches — taps fall through to composables beneath. Both the reel modal and the delete-confirm add `.pointerInput(Unit) { detectTapGestures {} }` to absorb. Any new modal/scrim must do the same.

## Code-generation workflow (mandatory)

This project has three custom agents in `.claude/agents/`. Whenever you generate non-trivial code, run them in this sequence:

1. **coder** (Sonnet) — implements the change.
2. **reviewer** (Opus) — independent review, read-only.
3. **tester** (Sonnet) — static defect hunt, no code written, no commands run.
4. If reviewer or tester reports blocking/major findings → re-spawn **coder** with their report attached and loop. **Hard cap: 2 fix loops.** If still not clean, summarise the remaining issues and ask the user.

### When the trio is mandatory
- A new `.kt` file is created.
- A new function is added.
- A diff touches control flow, IO, threading, lifecycle, Camera2/MediaCodec/MediaMuxer/MediaStore, or Compose UI structure.
- A Gradle/build-config change beyond a version bump.

### When to skip the trio (do it yourself, directly)
- Renames, typo fixes, single-line constant tweaks.
- Comment-only edits.
- Pure dependency version bumps in `libs.versions.toml`.
- Reverting a recent change verbatim.

### How to invoke
Use the Agent tool with `subagent_type: "coder" | "reviewer" | "tester"`. Each prompt must be self-contained — agents start with no prior conversation context. Include: the goal, the specific files/diff to look at, and (for reviewer/tester) the coder's report from the previous step.

### Don't bypass
"Quick fix" is the easiest way to reintroduce the bugs already documented in this file (async-codec landmine, AGP-9 plugin/DSL gotchas, etc.). Run the trio.

## What Claude should remember
- The app's subject is **Beyblade X** (filming battles, replaying the finish) — keep the UI themed to it; see Visual design.
- The UI depends on a bundled font at `app/src/main/res/font/vt323.ttf`. It is **not in the dev Mac's tree** (no network to fetch it) and the build fails on `R.font.vt323` without it — if a build error points there, the user just needs to drop the .ttf in on the build machine, it's not a code bug.
- Pause and ask before adding any new third-party dependency — every new artifact costs the user a trip to the other machine.
- When in doubt about a Gradle/AGP/Kotlin version, leave the existing version alone unless it's blocking.
- All "verify on device" steps land on the user, not on Claude.
- **AGP 9.x bundles the Kotlin Android plugin.** Do NOT apply `org.jetbrains.kotlin.android` — it double-registers the `kotlin` extension and Gradle sync fails with "extension is already registered with that name". Only `kotlin-compose` (the Compose compiler plugin) is applied separately. The Kotlin *version* still lives in `libs.versions.toml` because `kotlin-compose` references it.
- **AGP 9.x removed `kotlinOptions`.** Use the new DSL outside the `android { }` block:
  ```kotlin
  import org.jetbrains.kotlin.gradle.dsl.JvmTarget
  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
  ```

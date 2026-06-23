# BBRec

A landscape Android app for filming **Beyblade X** battles and instantly replaying the finish.

BBRec keeps a continuous rolling buffer of the last several seconds of video + audio in memory, so the moment a battle ends you can save a slow-mo replay of the last 5 or 10 seconds — no scrubbing, no rewinding a file. Full battles are recorded too, and everything lands in your phone's gallery under `Movies/BBRec/`.

- Camera: back camera, landscape only (follows the physical sensor — flip the phone 180° and the preview follows).
- Audio captured with video.
- Output: shared gallery via MediaStore, `Movies/BBRec/`.
- minSdk 29 (Android 10), targetSdk 35.

## User guide

The screen is split: **live preview** on the left (3/4), a **column of controls** on the right (1/4).

### Recording a battle
1. Tap **LAUNCH**. A "3, 2, 1, GO SHOOT!" countdown runs; recording starts on **GO** (not on the tap).
2. The button flips to **END** while recording (`● BATTLE` tally shows on screen).
3. Tap **END** to stop and save the whole LAUNCH→END span as one MP4 (`BATTLE SAVED`).

The record button always names its *next* action — LAUNCH when idle, END when recording. END is disabled during the countdown and while a replay is playing.

### Instant replay
Two buttons below the divider snapshot the rolling buffer and play it back inline at 0.5× slow-mo (the saved file stays normal speed):
- **Replay 5s** — last 5 seconds.
- **Replay 10s** — last 10 seconds.

Each button shows a spin gauge that winds up as the buffer fills; it's disabled until enough footage is held (≥5s / ≥10s) and while a replay is already playing. After playback the view returns to live camera. Saved as `REPLAY SAVED`.

> The rolling buffer is **cleared on LAUNCH** — replays during a session only pull footage captured after you pressed LAUNCH.

### Rewatching saved clips
When idle, a **reel** chip appears with your saved clips from this session. Tap it to open a gallery, tap a card to open the frame-accurate scrubber (play/pause, drag, ±1-frame stepping), or delete a clip.

### Things to know
- **Backgrounding the app saves an in-progress battle** (it doesn't discard it). But a hard kill (swipe from recents) loses the current battle — that's a deliberate limitation (no camera foreground service).
- The reel is **session-only**: saved clips listed in-app are lost when the process dies. The MP4 files themselves stay in your gallery.
- Resolution is device-best 16:9, capped at 1080p, 30 FPS. The chosen `{height}p · {fps}FPS` shows in the footer.

## Building

Build with `./gradlew assembleDebug` / `installDebug`. One asset is required and not in the repo: drop a VT323 font file at `app/src/main/res/font/vt323.ttf` (the build fails on `R.font.vt323` without it).

See [CLAUDE.md](CLAUDE.md) for dependency notes and AGP 9 gotchas.

## Architecture (overview)

Kotlin + Jetpack Compose, **Camera2 + MediaCodec** (not CameraX — CameraX's Recorder doesn't expose encoded frames for a rolling buffer). UI state lives directly in `CameraScreen` via `remember` — there is no ViewModel.

- `camera/CameraEngine.kt` — Camera2 session, video/audio MediaCodec, the encoded-sample ring buffer, and the live-session MediaMuxer.
- `camera/SampleRingBuffer.kt` — bounded deque of encoded samples; clips anchor on the latest keyframe at-or-before the cut point.
- `camera/ClipSaver.kt` — MediaStore insert.
- `ui/CameraScreen.kt` — main layout, overlays, inline replay (VideoView).
- `ui/ReelOverlay.kt` — saved-clip reel + frame-accurate ExoPlayer scrubber.
- `ui/PermissionScreen.kt`, `ui/Type.kt`, `MainActivity.kt` — permission flow, shared theme primitives, permission state machine.

See [CLAUDE.md](CLAUDE.md) for the detailed architecture, design system, and known simplifications.

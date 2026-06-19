# Action Replay — Claude working notes

## Hard constraint: dev/test split
- **This Mac (`m_vincent@`)**: development only. Network is restricted — assume **no** ability to fetch new artifacts, run Gradle sync against remote repos, install emulator system images, or hit Google Maven. Treat dependency additions and version bumps as costly: prefer what's already resolvable, and call out anything that would require a fresh download.
- **User's personal computer**: where `./gradlew assembleDebug`, `installDebug`, and emulator runs happen. All real verification lives there.
- Practical implication for Claude: **don't try to build, lint, or run unit tests from here**. Don't propose commands that need network. Ship code that is internally consistent and let the user run it on the other machine; if a Gradle/Kotlin/AGP version mismatch shows up there, fix by editing files, not by chasing it locally.

## Product spec (locked in 2026-06-19 conversation)
- Landscape-locked Android app, back camera.
- Layout: left **3/4** width = live preview, right **1/4** = vertical column of 4 square buttons.
- Buttons (top → bottom): **Play**, **Stop**, **Rewind 3s**, **Rewind 5s**.
- **Rolling buffer runs continuously from app launch** (≥5s of encoded video+audio held in memory).
- **Play** starts a full-session recording. **Stop** ends it and saves the full Play→Stop span as one MP4.
- **Rewind 3s / Rewind 5s**: snapshot last 3s/5s from the rolling buffer, save as MP4, then **play it back inline in the preview area**, then return to live camera.
- Rewind buttons are **disabled until the buffer holds ≥3s / ≥5s**.
- Audio is captured with video.
- Output goes to the **shared gallery** via MediaStore under `Movies/ActionReplay/`.

## Stack
- Kotlin + Jetpack Compose UI.
- **Camera2 + MediaCodec** (not CameraX) — CameraX Recorder doesn't expose encoded frames for a rolling buffer.
- minSdk 29 (Android 10), targetSdk current.
- Inline playback uses `android.widget.VideoView` (no extra dep).

## Architecture
- `camera/CameraEngine.kt` — owns Camera2 session, video MediaCodec (input surface), audio MediaCodec, and a `SampleRingBuffer` of encoded samples keyed by PTS. Also owns the live-session `MediaMuxer` when Play is active.
- `camera/SampleRingBuffer.kt` — bounded deque of encoded samples; clip extraction starts from the latest video keyframe at-or-before `(now − N seconds)`.
- `camera/ClipSaver.kt` — MediaStore insert helper.
- `ui/CameraScreen.kt` — Compose layout (preview + 4 buttons + replay overlay).
- `CameraViewModel.kt` — exposes `recording: Boolean`, `bufferSeconds: Float` (drives button enablement), `lastClipUri: Uri?`.
- `MainActivity.kt` — ComponentActivity, runtime permission flow, hosts `CameraScreen`.

## Known simplifications / `ponytail:` markers
- No foreground service: backgrounding the app stops capture. Add a `MediaProjection`-style foreground service later if "must keep recording while user switches apps" becomes a requirement.
- 1280×720 @ 30fps, GOP=1s, ~5 Mbps H.264, AAC 128 kbps — fixed; tune in `CameraEngine` if a device misbehaves.
- Ring buffer holds 8s of headroom (over the 5s spec) so the rewind-5s extraction can always anchor on a prior keyframe.
- Inline playback uses `VideoView` rather than Media3/ExoPlayer to avoid pulling in a large dep tree.

## What Claude should remember
- Pause and ask before adding any new third-party dependency — every new artifact costs the user a trip to the other machine.
- When in doubt about a Gradle/AGP/Kotlin version, leave the existing version alone unless it's blocking.
- All "verify on device" steps land on the user, not on Claude.
- **AGP 9.x bundles the Kotlin Android plugin.** Do NOT apply `org.jetbrains.kotlin.android` — it double-registers the `kotlin` extension and Gradle sync fails with "extension is already registered with that name". Only `kotlin-compose` (the Compose compiler plugin) is applied separately. The Kotlin *version* still lives in `libs.versions.toml` because `kotlin-compose` references it.
- **AGP 9.x removed `kotlinOptions`.** Use the new DSL outside the `android { }` block:
  ```kotlin
  import org.jetbrains.kotlin.gradle.dsl.JvmTarget
  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
  ```

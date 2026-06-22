package com.vincentla.bbrec.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import com.vincentla.bbrec.camera.CameraEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Palette
// ---------------------------------------------------------------------------

// Palette ("Beyblade X" — electric blue/cyan on ink) now lives in Type.kt, shared across screens.

private const val BUFFER_POLL_MS = 100L
private const val TRANSITION_MS = 500          // ease-in/ease-out per leg (dip-in, dip-out)
private const val SETTLE_MS = 120L             // ponytail: fixed settle for VideoView (SurfaceView) to swap under cover; bump if first frame flashes black on slow devices
private const val REWIND_SPEED = 0.5f          // inline replay speed; saved file stays normal-speed
// ponytail: last-resort cap. Longest replay is a 10s clip at 0.5× ≈ 20s + transitions; 30s clears
// it comfortably yet still rescues END if a VideoView never reports completion (some OEMs).
private const val REWIND_WATCHDOG_MS = 30_000L

// LocalContext can be a ContextWrapper; unwrap to the hosting Activity (null-safe).
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

// Slow-mo inline replay: mute (slowed audio is noise) and play at REWIND_SPEED.
// OEM-safe ordering: ensure the player is started first (some media stacks don't auto-start on
// setSpeed → frozen replay), then apply a *fresh* PlaybackParams. We deliberately do NOT read the
// getPlaybackParams() getter, which throws IllegalStateException on some OEM firmwares.
private fun MediaPlayer.playSlow() {
    setVolume(0f, 0f)
    if (!isPlaying) start()
    playbackParams = PlaybackParams().setSpeed(REWIND_SPEED)
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    // Counter-rotate the preview 180° in reverse-landscape. A 180° landscape↔landscape flip does
    // NOT change Configuration (same orientation + dp), so keying off LocalConfiguration never
    // recomposes — listen to actual display-rotation changes instead.
    var displayRotation by remember { mutableIntStateOf(view.display?.rotation ?: Surface.ROTATION_0) }
    DisposableEffect(view) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                displayRotation = view.display?.rotation ?: Surface.ROTATION_0
            }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }
        dm.registerDisplayListener(listener, mainHandler)
        onDispose { dm.unregisterDisplayListener(listener) }
    }
    val previewRotation = when (displayRotation) {
        Surface.ROTATION_180, Surface.ROTATION_270 -> 180f
        else -> 0f
    }

    var bufferedSec by remember { mutableStateOf(0f) }
    var cameraUptimeSec by remember { mutableStateOf(0f) }
    var isRecording by remember { mutableStateOf(false) }
    var requestedUri by remember { mutableStateOf<Uri?>(null) }   // desired clip (null = live)
    var displayedUri by remember { mutableStateOf<Uri?>(null) }   // clip actually mounted in VideoView
    var sessionStartMs by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(0L) }
    var rewindLabel by remember { mutableStateOf("") }
    var rewindVisible by remember { mutableStateOf(false) }
    var rewindBusy by remember { mutableStateOf(false) }   // true from rewind tap until the preview is over
    var captureSpec by remember { mutableStateOf("") }     // e.g. "1080p · 30FPS", reported by the engine at start
    var countdown by remember { mutableStateOf<String?>(null) }  // "3"/"2"/"1"/"GO SHOOT!" during launch, else null
    var status by remember { mutableStateOf<StatusMsg?>(null) }   // transient branded confirmation/error bug, else null
    val savedClips = remember { mutableStateListOf<SavedClip>() } // session-scoped reel (in-memory, lost on process death)
    var reelOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var scrimTarget by remember { mutableFloatStateOf(0f) }
    val scrimAlpha by animateFloatAsState(
        targetValue = scrimTarget,
        animationSpec = tween(TRANSITION_MS, easing = EaseInOut),
        label = "scrim",
    )

    LaunchedEffect(rewindLabel, rewindVisible) {
        if (rewindVisible) {
            delay(700)
            rewindVisible = false
        }
    }

    // Auto-dismiss the status bug. A new message re-keys this effect, cancelling the old timer.
    LaunchedEffect(status) {
        if (status != null) {
            delay(1600)
            status = null
        }
    }

    // Dip-to-black scene transition (both directions). The scrim is a Compose Box
    // drawn on the window surface, above the VideoView's media-overlay surface, so
    // it hides the SurfaceView pop that animating the VideoView's own alpha cannot.
    LaunchedEffect(requestedUri) {
        if (requestedUri == displayedUri) {
            scrimTarget = 0f                // already settled on the desired scene; keep scrim down
            // Invariant: a live scene never coexists with the replay lock. Clearing here makes it
            // impossible for any early-return path to strand rewindBusy=true on the live view.
            if (displayedUri == null) rewindBusy = false
            return@LaunchedEffect
        }
        scrimTarget = 1f                    // dip to black over the current scene
        delay(TRANSITION_MS.toLong())       // wait for scrim fully opaque
        displayedUri = requestedUri         // swap mounted clip under cover (mount or unmount)
        delay(SETTLE_MS)                    // let VideoView surface settle / show first frame
        scrimTarget = 0f                    // reveal the new scene
        if (displayedUri == null) rewindBusy = false   // preview over, back to live → re-enable rewind
    }

    // Watchdog: if a replay's VideoView never reports completion/error (some OEMs), force a return
    // to live. rewindBusy now also gates END, so without this a stuck replay would leave the user
    // unable to end and save their battle for the rest of the session. Cancels itself when the
    // normal path clears rewindBusy (the key change re-runs this effect).
    LaunchedEffect(rewindBusy) {
        if (rewindBusy) {
            delay(REWIND_WATCHDOG_MS)
            if (rewindBusy) requestedUri = null   // unmount replay → transition above returns to live
        }
    }

    // Lock orientation while recording: MediaMuxer's orientation hint is fixed at file creation,
    // so a mid-record 180° flip would otherwise produce a half-upside-down clip. Free to flip otherwise.
    LaunchedEffect(isRecording) {
        activity?.requestedOrientation =
            if (isRecording) ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    val engine = remember {
        CameraEngine(context, object : CameraEngine.Listener {
            override fun onClipSaved(uri: Uri?, label: String) {
                mainHandler.post {
                    if (uri != null) {
                        requestedUri = uri
                        savedClips.add(SavedClip(uri, ClipKind.REPLAY))
                        status = StatusMsg("REPLAY SAVED", error = false)
                    } else {
                        rewindBusy = false   // save failed, no preview will play → re-enable rewind
                        status = StatusMsg("COULDN'T SAVE REPLAY", error = true)
                    }
                }
            }
            override fun onSessionSaved(uri: Uri?) {
                mainHandler.post {
                    isRecording = false
                    // null covers both a real save failure and a deliberate discard (END before the
                    // first keyframe). "NOT SAVED" is honest for both without overclaiming a failure.
                    if (uri != null) {
                        savedClips.add(SavedClip(uri, ClipKind.BATTLE))
                        status = StatusMsg("BATTLE SAVED", error = false)
                    } else {
                        status = StatusMsg("BATTLE NOT SAVED", error = true)
                    }
                }
            }
            override fun onError(message: String, cause: Throwable?) {
                mainHandler.post {
                    rewindBusy = false   // engine error path (e.g. rewindAndSave threw) must re-enable rewind
                    status = StatusMsg(message.uppercase(java.util.Locale.ROOT), error = true)
                }
            }
            override fun onBufferProgress(seconds: Float) {
                mainHandler.post { bufferedSec = seconds }
            }
            override fun onCaptureConfig(width: Int, height: Int, fps: Int) {
                mainHandler.post { captureSpec = "${height}p · ${fps}FPS" }
            }
        })
    }

    // Shared exit path for both teardown triggers — surface destroyed on background, and composition
    // disposed on recreation (e.g. a config change). Save any in-progress battle (no-op if none),
    // tear the engine down, and drop any inline playback so we return to a clean live view instead of
    // a stuck busy-lock. ponytail: a process kill (swipe from recents) runs NEITHER path, so that
    // battle is lost — surviving a kill needs a camera foreground service (deliberately out of scope).
    val saveAndTeardown = {
        engine.endSession()
        engine.stop()
        rewindBusy = false
        requestedUri = null
        displayedUri = null
    }

    DisposableEffect(Unit) { onDispose { saveAndTeardown() } }

    LaunchedEffect(Unit) {
        while (true) {
            bufferedSec = engine.bufferedSeconds()
            // ponytail: fallback timer so emulators with starved encoder surfaces still
            // enable rewind buttons; on real devices bufferedSec from encoder wins.
            cameraUptimeSec = (cameraUptimeSec + BUFFER_POLL_MS / 1000f).coerceAtMost(13f)
            nowMs = System.currentTimeMillis()
            delay(BUFFER_POLL_MS)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(backgroundBrush()).scanlines()) {

        Row(modifier = Modifier.fillMaxSize()) {

            // ---- Preview pane (3/4) -------------------------------------
            Box(
                modifier = Modifier
                    .weight(3f)
                    .fillMaxHeight()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Divider, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        cameraUptimeSec = 0f
                                        engine.start(holder.surface)
                                    }
                                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) {}
                                    // Save-on-background: finalize + publish an in-progress battle (and
                                    // drop any inline playback) instead of letting stop() discard it.
                                    // Same path as composition disposal — see saveAndTeardown.
                                    override fun surfaceDestroyed(holder: SurfaceHolder) { saveAndTeardown() }
                                })
                            }
                        },
                        update = { it.rotation = previewRotation },
                    )

                    displayedUri?.let { uri ->
                        androidx.compose.ui.viewinterop.AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    tag = uri          // fix1: prevent update re-init on first call
                                    setZOrderMediaOverlay(true)   // fix2: overlay above camera SurfaceView
                                    // Guard: a late completion/error from a recycled VideoView must not
                                    // cancel a clip that has since been requested (rapid re-rewind).
                                    setOnCompletionListener { mainHandler.post { if (requestedUri == uri) requestedUri = null } }
                                    setOnErrorListener { _, _, _ -> mainHandler.post { if (requestedUri == uri) { requestedUri = null; status = StatusMsg("CLIP UNAVAILABLE", error = true) } }; true }
                                    setOnPreparedListener { mp -> mp.playSlow() }
                                    // ponytail: no MediaController — inline replay auto-plays once and
                                    // returns to live. Transport controls would let the user pause/scrub
                                    // and never fire onCompletion, stranding the rewind-busy lock.
                                    setVideoURI(uri)
                                    start()
                                }
                            },
                            update = { v ->
                                if (v.tag != uri) {
                                    v.tag = uri
                                    v.setOnCompletionListener(null)
                                    v.setOnErrorListener(null)
                                    v.setOnPreparedListener { mp ->
                                        v.setOnCompletionListener { mainHandler.post { if (requestedUri == uri) requestedUri = null } }
                                        v.setOnErrorListener { _, _, _ -> mainHandler.post { if (requestedUri == uri) { requestedUri = null; status = StatusMsg("CLIP UNAVAILABLE", error = true) } }; true }
                                        mp.playSlow()
                                    }
                                    v.setVideoURI(uri)
                                }
                            },
                        )
                    }

                    // Skip-to-live: the clip is already saved (rewindAndSave wrote it on tap), so the
                    // inline replay is just a preview — let the user bail back to the live battle
                    // instead of waiting out the full 0.5× playback. Tapping anywhere on the replay
                    // sets requestedUri = null, the same thing onCompletion does, which drives the
                    // return transition and clears rewindBusy. Not a MediaController (no pause/scrub),
                    // so it can't strand the lock. The labelled chip below is the discoverable/a11y path.
                    if (displayedUri != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) { detectTapGestures { requestedUri = null } },
                        )
                    }

                    // X-slash wipe — replaces the dip-to-black. The electric-blue fill at full
                    // alpha still fully covers the SurfaceView swap at peak (same cover guarantee
                    // as before); the white/cyan X grows from center as the scene flips, giving
                    // the franchise's logo gesture as the transition. Drawn above the VideoView's
                    // media-overlay surface.
                    if (scrimAlpha > 0f) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val t = scrimAlpha
                            drawRect(color = XBlue, alpha = t)   // full cover at peak hides the swap
                            val hw = size.width / 2f
                            val hh = size.height / 2f
                            val sw = 9.dp.toPx()
                            drawLine(
                                color = Color.White,
                                start = androidx.compose.ui.geometry.Offset(hw - hw * t, hh - hh * t),
                                end = androidx.compose.ui.geometry.Offset(hw + hw * t, hh + hh * t),
                                strokeWidth = sw, cap = StrokeCap.Round, alpha = t,
                            )
                            drawLine(
                                color = Cyan,
                                start = androidx.compose.ui.geometry.Offset(hw - hw * t, hh + hh * t),
                                end = androidx.compose.ui.geometry.Offset(hw + hw * t, hh - hh * t),
                                strokeWidth = sw, cap = StrokeCap.Round, alpha = t,
                            )
                        }
                    }

                    RewindOverlay(visible = rewindVisible, label = rewindLabel)

                    countdown?.let { CountdownOverlay(it) }

                    // LIVE pill + timecode only while the live scene shows; both hidden during replay.
                    if (isRecording && displayedUri == null) {
                        LivePill(modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
                        Timecode(
                            elapsedMs = (nowMs - sessionStartMs).coerceAtLeast(0),
                            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                        )
                    }
                    if (displayedUri != null) {
                        RewindPill(modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
                        BackToLiveChip(
                            onClick = { requestedUri = null },
                            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                        )
                    }

                    // Branded save/error confirmation — replaces system Toasts (cohesive type, more
                    // visible, and never leaks internal labels like the old "Saved rewind5s").
                    // Bottom-center keeps it clear of the top-corner HUD (tally/timecode/replay bug).
                    status?.let {
                        StatusBug(it, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
                    }

                    // First-run nudge: only before the very first launch this session (sessionStartMs
                    // is still 0), on the idle live view, and never under a status bug (shares BottomCenter).
                    if (sessionStartMs == 0L && !isRecording && countdown == null && displayedUri == null && status == null) {
                        IdleHint(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
                    }

                    // Reel opener: only on the idle live view (not recording/counting/replaying), and only
                    // when there's something to show. Idle-only avoids rewatch audio bleeding into a live mic.
                    if (!reelOpen && !isRecording && countdown == null && displayedUri == null && savedClips.isNotEmpty()) {
                        ReelChip(
                            latest = savedClips.last(),
                            count = savedClips.size,
                            onClick = { reelOpen = true },
                            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                        )
                    }
                }

                FooterLabel(
                    text = captureSpec,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 20.dp),
                )
            }

            // ---- Right panel (1/4) --------------------------------------
            val effectiveBuffer = maxOf(bufferedSec, cameraUptimeSec)
            ControlPanel(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                isRecording = isRecording,
                countingDown = countdown != null,
                bufferedSec = effectiveBuffer,
                rewindBusy = rewindBusy,
                onPlay = {
                    // "3, 2, 1, GO SHOOT!" — recording arms exactly on GO, like a real launch.
                    if (countdown == null && !isRecording) {
                        scope.launch {
                            try {
                                for (n in listOf("3", "2", "1")) { countdown = n; delay(650) }
                                countdown = "GO SHOOT!"
                                // Lock orientation BEFORE beginSession so the muxer's orientationHint and
                                // the locked preview are taken from one consistent rotation.
                                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                                // Reset BOTH buffer clocks so rewind re-earns its threshold from launch.
                                bufferedSec = 0f
                                cameraUptimeSec = 0f
                                // beginSession() returns false if the engine stopped during the countdown
                                // (e.g. app backgrounded). Abort cleanly instead of faking a recording.
                                if (engine.beginSession()) {
                                    sessionStartMs = System.currentTimeMillis()
                                    isRecording = true
                                    delay(550)    // hold "GO SHOOT!" over the now-live preview, then clear
                                }
                            } finally {
                                // Always clear, even on abort/cancellation/throw, so PLAY can never get
                                // stuck disabled behind a frozen countdown overlay.
                                countdown = null
                                // If the launch aborted (engine stopped during countdown), the orientation
                                // was already pinned LOCKED but isRecording never flipped, so the
                                // LaunchedEffect(isRecording) won't restore it. Un-pin here.
                                if (!isRecording) {
                                    activity?.requestedOrientation =
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                }
                            }
                        }
                    }
                },
                onStop = { engine.endSession() },
                onRewindShort = {
                    rewindBusy = true
                    rewindLabel = "-5s"
                    rewindVisible = true
                    engine.rewindAndSave(5)
                },
                onRewindLong = {
                    rewindBusy = true
                    rewindLabel = "-10s"
                    rewindVisible = true
                    engine.rewindAndSave(10)
                },
            )
        }

        // Reel modal — covers the whole screen (preview + panel). Self-contained; only ever opened
        // from the idle live view. Rewatch hands off to the existing inline player at normal speed.
        if (reelOpen) {
            ReelOverlay(
                clips = savedClips,
                onClose = { reelOpen = false },
                onDelete = { clip ->
                    savedClips.remove(clip)
                    // Delete the owned MediaStore item off the main thread — allowed on API 29+ with no
                    // extra permission. If it fails the clip is already gone from the reel (acceptable).
                    Thread { runCatching { context.contentResolver.delete(clip.uri, null, null) } }.start()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Right panel
// ---------------------------------------------------------------------------

@Composable
private fun ControlPanel(
    modifier: Modifier,
    isRecording: Boolean,
    countingDown: Boolean,
    bufferedSec: Float,
    rewindBusy: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onRewindShort: () -> Unit,
    onRewindLong: () -> Unit,
) {
    Column(
        modifier = modifier.padding(start = 12.dp, end = 22.dp, top = 16.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.weight(1f))

        // One record toggle: LAUNCH the battle when idle, END (save) it when live. The label
        // always states what the next press does, so there's no separate STOP to confuse. END is
        // disabled while an inline replay is playing so a reflex tap can't cut the battle short —
        // it re-enables the instant the replay returns to live.
        val live = isRecording
        CircleButton(
            label = if (live) "END" else "LAUNCH",
            contentDescription = if (live) "End and save the battle" else "Launch — start recording after a countdown",
            // END disabled during the countdown ("GO SHOOT!" hold) and during an inline replay
            // (so a reflex tap can't cut the battle short); LAUNCH disabled during the countdown.
            // (Reel rewatch runs in its own full-screen modal, so it can't reach these buttons.)
            enabled = if (live) (!rewindBusy && !countingDown) else !countingDown,
            ringColor = if (live) StrikeRed else Cyan,
            onClick = if (live) onStop else onPlay,
        ) { s -> if (live) drawStopIcon(s, StrikeRed) else drawPlayIcon(s, Cyan) }

        // Hairline splits the two jobs: run the battle (above) vs. replay a moment (below).
        Box(modifier = Modifier.width(28.dp).height(1.dp).background(Divider))

        CircleButton(
            label = "REPLAY\n5 SEC",
            contentDescription = "Replay the last 5 seconds of the battle",
            enabled = isRecording && !rewindBusy && bufferedSec >= 5f,
            progress = if (isRecording) (bufferedSec / 5f).coerceIn(0f, 1f) else 1f,
            dial = true,
            ringColor = XBlue,
            onClick = onRewindShort,
        ) { drawRewindIcon(it, XBlue) }

        CircleButton(
            label = "REPLAY\n10 SEC",
            contentDescription = "Replay the last 10 seconds of the battle",
            enabled = isRecording && !rewindBusy && bufferedSec >= 10f,
            progress = if (isRecording) (bufferedSec / 10f).coerceIn(0f, 1f) else 1f,
            dial = true,
            ringColor = XBlue,
            onClick = onRewindLong,
        ) { drawRewindIcon(it, XBlue) }

        Spacer(Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------------------
// Rewind overlay — extracted so AnimatedVisibility resolves to the top-level
// overload unambiguously (no BoxScope in scope avoids K2 UNIT_CALL_WITH_RECEIVER)
// ---------------------------------------------------------------------------

@Composable
private fun RewindOverlay(visible: Boolean, label: String) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(280, easing = EaseInOut))
            + scaleIn(initialScale = 0.85f, animationSpec = tween(280, easing = EaseInOut)),
        exit = fadeOut(animationSpec = tween(420, easing = EaseInOut))
            + scaleOut(targetScale = 1.15f, animationSpec = tween(420, easing = EaseInOut)),
    ) {
        Text(
            text = label,
            style = TextStyle(
                brush = Brush.linearGradient(listOf(Cyan, XBlue)),
                fontFamily = BattleFont,
                fontWeight = FontWeight.Black,
                fontSize = 96.sp,
                letterSpacing = 4.sp,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// "3, 2, 1, GO SHOOT!" launch countdown
// ---------------------------------------------------------------------------

@Composable
private fun CountdownOverlay(label: String) {
    val go = label == "GO SHOOT!"
    Text(
        text = label,
        textAlign = TextAlign.Center,
        style = TextStyle(
            color = if (go) Volt else Cyan,
            fontFamily = BattleFont,
            fontWeight = FontWeight.Black,
            fontSize = if (go) 64.sp else 120.sp,
            letterSpacing = if (go) 4.sp else 0.sp,
        ),
    )
}

// ---------------------------------------------------------------------------
// Circle button
// ---------------------------------------------------------------------------

@Composable
private fun CircleButton(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    ringColor: Color,
    onClick: () -> Unit,
    progress: Float = 1f,          // for dial buttons: how close rewind is to enabling (0..1)
    dial: Boolean = false,         // true → draw the jog/shuttle tick ring (the signature)
    iconDraw: (androidx.compose.ui.graphics.drawscope.DrawScope.(Float) -> Unit),
) {
    val alpha = if (enabled) 1f else 0.3f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Panel.copy(alpha = alpha))
                .border(1.5.dp, ringColor.copy(alpha = alpha), CircleShape)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { this.contentDescription = contentDescription; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            if (dial) SpinGauge(progress = progress, color = ringColor, enabled = enabled)
            Canvas(modifier = Modifier.size(22.dp).alpha(alpha)) {
                iconDraw(size.minDimension)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            textAlign = TextAlign.Center,
            color = Steel.copy(alpha = if (enabled) 1f else 0.5f),
            style = TextStyle(
                fontFamily = BattleFont,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
                lineHeight = 12.sp,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Spin gauge — the signature. RPM ticks around the rim wind up (in `color`) as the
// rolling buffer accrues toward the replay threshold; the fill stays fully visible even
// while the button is disabled, since it IS the readiness feedback. Once ready, a cyan
// spark orbits the rim — the bey is still spinning, ready for an instant replay. Only
// composed for dial buttons, so PLAY/STOP never run the infinite transition.
// ---------------------------------------------------------------------------

@Composable
private fun SpinGauge(progress: Float, color: Color, enabled: Boolean) {
    val context = LocalContext.current
    val reduced = remember { isReducedMotion(context) }   // read once, not per recomposition
    val spin = rememberInfiniteTransition(label = "spin")
    val spinAngle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "rpm",
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val ticks = 24
        val lit = (progress.coerceIn(0f, 1f) * ticks).toInt()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val inset = 3.dp.toPx()
        val outerR = size.minDimension / 2f - inset
        val innerR = outerR - 5.dp.toPx()
        val sw = 1.5.dp.toPx()
        for (i in 0 until ticks) {
            // start at top (-90°), go clockwise
            val ang = (-PI / 2.0) + (2.0 * PI * i / ticks)
            val ca = cos(ang).toFloat()
            val sa = sin(ang).toFloat()
            val tickColor = if (i < lit) color else Steel.copy(alpha = 0.4f)
            drawLine(
                color = tickColor,
                start = androidx.compose.ui.geometry.Offset(cx + innerR * ca, cy + innerR * sa),
                end = androidx.compose.ui.geometry.Offset(cx + outerR * ca, cy + outerR * sa),
                strokeWidth = sw,
                cap = StrokeCap.Round,
            )
        }
        if (enabled) {
            // Orbiting spark when ready (reads spinAngle in the draw phase, no recomposition).
            // Under reduced motion, pin it to the top so "ready" still has a distinct mark, no spin.
            drawArc(
                color = Cyan,
                startAngle = if (reduced) -90f else spinAngle,
                sweepAngle = 42f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(cx - outerR, cy - outerR),
                size = androidx.compose.ui.geometry.Size(outerR * 2f, outerR * 2f),
                style = Stroke(width = sw * 1.8f, cap = StrokeCap.Round),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Icon painters
// ---------------------------------------------------------------------------

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlayIcon(s: Float, color: Color) {
    val path = Path().apply {
        moveTo(s * 0.20f, s * 0.10f)
        lineTo(s * 0.20f, s * 0.90f)
        lineTo(s * 0.85f, s * 0.50f)
        close()
    }
    drawPath(path, color)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStopIcon(s: Float, color: Color) {
    val side = s * 0.60f
    val origin = (s - side) / 2f
    drawRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(origin, origin),
        size = androidx.compose.ui.geometry.Size(side, side),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRewindIcon(s: Float, color: Color) {
    val stroke = Stroke(width = s * 0.10f)
    val pad = s * 0.18f
    val arcSize = androidx.compose.ui.geometry.Size(s - 2 * pad, s - 2 * pad)
    val topLeft = androidx.compose.ui.geometry.Offset(pad, pad)
    // 270° counter-clockwise arc — open at top-right.
    drawArc(
        color = color,
        startAngle = -45f,
        sweepAngle = -270f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = stroke,
    )
    // Arrowhead at the start of the arc (pointing roughly up-left).
    val cx = s / 2f
    val cy = s / 2f
    val r = (s - 2 * pad) / 2f
    val startRad = (-45.0 * PI / 180.0)
    val tipX = (cx + r * cos(startRad)).toFloat()
    val tipY = (cy + r * sin(startRad)).toFloat()
    val ah = s * 0.18f
    val head = Path().apply {
        moveTo(tipX, tipY)
        lineTo(tipX + ah, tipY - ah * 0.2f)
        lineTo(tipX + ah * 0.2f, tipY + ah)
        close()
    }
    drawPath(head, color)
}

// ---------------------------------------------------------------------------
// HUD elements
// ---------------------------------------------------------------------------

@Composable
private fun LivePill(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(StrikeRed)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "BATTLE",
            color = Color.White,
            style = TextStyle(
                fontFamily = BattleFont,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
            ),
        )
    }
}

// During-replay bug — a dark tag with the X-blue edge and cyan readout. The inline player is the
// 0.5× auto-rewind only; reel rewatch has its own ExoPlayer viewer.
@Composable
private fun RewindPill(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Panel)
            .border(1.dp, XBlue, RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "> REPLAY 0.5x",
            color = Cyan,
            style = TextStyle(
                fontFamily = BattleFont,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
            ),
        )
    }
}

// Tappable "return to live" control shown during a replay (the whole replay is also tappable;
// this is the discoverable, TalkBack-reachable affordance). Button-styled so it reads as pressable.
@Composable
private fun BackToLiveChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Panel)
            .border(1.dp, Cyan, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Back to the live battle"; role = Role.Button }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(6.dp).clip(CircleShape).background(StrikeRed),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "BACK TO LIVE",
            color = Cyan,
            style = TextStyle(
                fontFamily = BattleFont,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
            ),
        )
    }
}

// Transient branded status bug (save confirmation / error) — replaces system Toasts.
private data class StatusMsg(val text: String, val error: Boolean)

@Composable
private fun StatusBug(msg: StatusMsg, modifier: Modifier = Modifier) {
    val accent = if (msg.error) StrikeRed else Cyan
    Row(
        modifier = modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Panel)
            .border(1.dp, accent, RoundedCornerShape(3.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(6.dp).clip(CircleShape).background(accent),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = msg.text,
            color = accent,
            style = TextStyle(
                fontFamily = BattleFont,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
            ),
        )
    }
}

// First-run nudge shown on the idle live view before the first launch.
@Composable
private fun IdleHint(modifier: Modifier = Modifier) {
    Text(
        text = "TAP LAUNCH TO START",
        modifier = modifier,
        color = Cyan.copy(alpha = 0.8f),
        style = TextStyle(
            fontFamily = BattleFont,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 2.sp,
        ),
    )
}

@Composable
private fun Timecode(elapsedMs: Long, modifier: Modifier = Modifier) {
    val total = elapsedMs / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    val frames = ((elapsedMs % 1000) * 30 / 1000)
    val text = "%02d:%02d:%02d:%02d".format(hours, minutes, seconds, frames)
    Box(
        modifier = modifier
            .border(1.dp, XBlue, RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = Cyan,
            style = TextStyle(
                fontFamily = BattleFont,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
            ),
        )
    }
}

@Composable
private fun FooterLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = Steel,
        style = TextStyle(
            fontFamily = BattleFont,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
        ),
    )
}

// ---------------------------------------------------------------------------
// Background
// ---------------------------------------------------------------------------

private fun backgroundBrush(): Brush =
    Brush.verticalGradient(listOf(BgTop, BgBottom))

private fun Modifier.scanlines(): Modifier = this.drawBehind {
    val spacing = 3.dp.toPx()
    val color = Color.White.copy(alpha = 0.03f)
    val dash = PathEffect.dashPathEffect(floatArrayOf(2f, 2f), 0f)  // hoisted: was reallocated per line per frame
    var y = 0f
    while (y < size.height) {
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(0f, y),
            end = androidx.compose.ui.geometry.Offset(size.width, y),
            strokeWidth = 1f,
            pathEffect = dash,
        )
        y += spacing
    }
}

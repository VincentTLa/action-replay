package com.vincentla.action_replay.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vincentla.action_replay.camera.CameraEngine
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Palette
// ---------------------------------------------------------------------------

private val BgTop = Color(0xFF06061A)
private val BgBottom = Color(0xFF1B0A2E)
private val Cyan = Color(0xFF22D3EE)
private val CyanDim = Color(0xFF0E7490)
private val Purple = Color(0xFFA855F7)
private val RedLive = Color(0xFFEF4444)
private val TextDim = Color(0xFF8A8FA0)
private val RingFill = Color(0xFF0B1428)
private val Divider = Color(0xFF1F2A44)

private const val BUFFER_POLL_MS = 100L
private const val TRANSITION_MS = 500          // ease-in/ease-out per leg (dip-in, dip-out)
private const val SETTLE_MS = 120L             // ponytail: fixed settle for VideoView (SurfaceView) to swap under cover; bump if first frame flashes black on slow devices
private const val REWIND_SPEED = 0.5f          // inline replay speed; saved file stays normal-speed

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
// setSpeed on a prepared player also starts it, so no separate start() needed.
private fun MediaPlayer.playSlow() {
    setVolume(0f, 0f)
    playbackParams = playbackParams.setSpeed(REWIND_SPEED)
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

    // Dip-to-black scene transition (both directions). The scrim is a Compose Box
    // drawn on the window surface, above the VideoView's media-overlay surface, so
    // it hides the SurfaceView pop that animating the VideoView's own alpha cannot.
    LaunchedEffect(requestedUri) {
        if (requestedUri == displayedUri) {
            scrimTarget = 0f                // already settled on the desired scene; keep scrim down
            return@LaunchedEffect
        }
        scrimTarget = 1f                    // dip to black over the current scene
        delay(TRANSITION_MS.toLong())       // wait for scrim fully opaque
        displayedUri = requestedUri         // swap mounted clip under cover (mount or unmount)
        delay(SETTLE_MS)                    // let VideoView surface settle / show first frame
        scrimTarget = 0f                    // reveal the new scene
        if (displayedUri == null) rewindBusy = false   // preview over, back to live → re-enable rewind
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
                        Toast.makeText(context, "Saved $label", Toast.LENGTH_SHORT).show()
                    } else {
                        rewindBusy = false   // save failed, no preview will play → re-enable rewind
                        Toast.makeText(context, "Clip save failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            override fun onSessionSaved(uri: Uri?) {
                mainHandler.post {
                    isRecording = false
                    val msg = if (uri != null) "Session saved" else "Session save failed"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
            override fun onError(message: String, cause: Throwable?) {
                mainHandler.post {
                    rewindBusy = false   // engine error path (e.g. rewindAndSave threw) must re-enable rewind
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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

    DisposableEffect(Unit) { onDispose { engine.stop() } }

    LaunchedEffect(Unit) {
        while (true) {
            bufferedSec = engine.bufferedSeconds()
            // ponytail: fallback timer so emulators with starved encoder surfaces still
            // enable rewind buttons; on real devices bufferedSec from encoder wins.
            cameraUptimeSec = (cameraUptimeSec + BUFFER_POLL_MS / 1000f).coerceAtMost(8f)
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
                                    override fun surfaceDestroyed(holder: SurfaceHolder) { engine.stop() }
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
                                    setOnErrorListener { _, _, _ -> mainHandler.post { if (requestedUri == uri) requestedUri = null }; true }
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
                                        v.setOnErrorListener { _, _, _ -> mainHandler.post { if (requestedUri == uri) requestedUri = null }; true }
                                        mp.playSlow()
                                    }
                                    v.setVideoURI(uri)
                                }
                            },
                        )
                    }

                    // Dip-to-black scrim — drawn above the VideoView's overlay surface.
                    if (scrimAlpha > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(scrimAlpha)
                                .background(Color.Black),
                        )
                    }

                    RewindOverlay(visible = rewindVisible, label = rewindLabel)

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
                    }
                }

                FooterLabel(
                    text = "ACTION REPLAY CAM",
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 20.dp),
                )
                FooterLabel(
                    text = captureSpec,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 20.dp),
                )
            }

            // ---- Right panel (1/4) --------------------------------------
            val effectiveBuffer = maxOf(bufferedSec, cameraUptimeSec)
            ActionReplayPanel(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                isRecording = isRecording,
                bufferedSec = effectiveBuffer,
                rewindBusy = rewindBusy,
                onPlay = {
                    // Lock orientation BEFORE beginSession so the muxer's orientationHint and the
                    // locked preview are taken from one consistent rotation (closes the mid-tap flip race).
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                    // Reset BOTH buffer clocks so rewind re-earns its threshold from Play. Without
                    // zeroing bufferedSec here, its stale pre-clear value lingers until onBufferProgress
                    // fires and could briefly re-enable the rewind buttons (and flash the fill ring full).
                    bufferedSec = 0f
                    cameraUptimeSec = 0f      // reset fallback clock so rewind buttons re-earn their threshold
                    engine.beginSession()
                    sessionStartMs = System.currentTimeMillis()
                    isRecording = true
                },
                onStop = { engine.endSession() },
                onRewind3 = {
                    rewindBusy = true
                    rewindLabel = "-3s"
                    rewindVisible = true
                    engine.rewindAndSave(3)
                },
                onRewind5 = {
                    rewindBusy = true
                    rewindLabel = "-5s"
                    rewindVisible = true
                    engine.rewindAndSave(5)
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Right panel
// ---------------------------------------------------------------------------

@Composable
private fun ActionReplayPanel(
    modifier: Modifier,
    isRecording: Boolean,
    bufferedSec: Float,
    rewindBusy: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onRewind3: () -> Unit,
    onRewind5: () -> Unit,
) {
    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.weight(1f))

        CircleButton(
            label = "PLAY",
            contentDescription = "Start recording",
            enabled = !isRecording,
            ringColor = Cyan,
            onClick = onPlay,
        ) { drawPlayIcon(it, Cyan) }

        CircleButton(
            label = "STOP",
            contentDescription = "Stop and save recording",
            enabled = isRecording,
            ringColor = RedLive,
            onClick = onStop,
        ) { drawStopIcon(it, RedLive) }

        CircleButton(
            label = "REWIND\n3 SEC",
            contentDescription = "Rewind and replay the last 3 seconds",
            enabled = isRecording && !rewindBusy && bufferedSec >= 3f,
            progress = if (isRecording) (bufferedSec / 3f).coerceIn(0f, 1f) else 1f,
            ringColor = Purple,
            onClick = onRewind3,
        ) { drawRewindIcon(it, Purple) }

        CircleButton(
            label = "REWIND\n5 SEC",
            contentDescription = "Rewind and replay the last 5 seconds",
            enabled = isRecording && !rewindBusy && bufferedSec >= 5f,
            progress = if (isRecording) (bufferedSec / 5f).coerceIn(0f, 1f) else 1f,
            ringColor = Purple,
            onClick = onRewind5,
        ) { drawRewindIcon(it, Purple) }

        Spacer(Modifier.weight(1f))
        LiveDotFooter()
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
                brush = Brush.linearGradient(listOf(Cyan, Purple)),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 96.sp,
                letterSpacing = 4.sp,
            ),
        )
    }
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
    progress: Float = 1f,          // <1f draws a buffer-fill ring (how close rewind is to enabling)
    iconDraw: (androidx.compose.ui.graphics.drawscope.DrawScope.(Float) -> Unit),
) {
    val alpha = if (enabled) 1f else 0.3f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(RingFill.copy(alpha = alpha))
                .border(1.5.dp, ringColor.copy(alpha = alpha), CircleShape)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { this.contentDescription = contentDescription; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            // Buffer-fill ring: shows the rolling buffer accruing toward the rewind threshold.
            if (progress < 1f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val sw = 2.dp.toPx()
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(sw / 2f, sw / 2f),
                        size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw),
                        style = Stroke(width = sw, cap = StrokeCap.Round),
                    )
                }
            }
            Canvas(modifier = Modifier.size(22.dp).alpha(alpha)) {
                iconDraw(size.minDimension)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            textAlign = TextAlign.Center,
            color = TextDim.copy(alpha = if (enabled) 1f else 0.5f),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
                lineHeight = 11.sp,
            ),
        )
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
            .background(RedLive)
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
            text = "LIVE",
            color = Color.White,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
            ),
        )
    }
}

@Composable
private fun RewindPill(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Purple)
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
            text = "REWIND 0.5x",
            color = Color.White,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
            ),
        )
    }
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
            .border(1.dp, CyanDim, RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = Cyan,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
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
        color = TextDim,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
        ),
    )
}

@Composable
private fun LiveDotFooter() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(RedLive),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "LIVE",
            color = RedLive,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Background
// ---------------------------------------------------------------------------

private fun backgroundBrush(): Brush =
    Brush.verticalGradient(listOf(BgTop, BgBottom))

private fun Modifier.scanlines(): Modifier = this.drawBehind {
    val spacing = 3.dp.toPx()
    val color = Color.White.copy(alpha = 0.03f)
    var y = 0f
    while (y < size.height) {
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(0f, y),
            end = androidx.compose.ui.geometry.Offset(size.width, y),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 2f), 0f),
        )
        y += spacing
    }
}

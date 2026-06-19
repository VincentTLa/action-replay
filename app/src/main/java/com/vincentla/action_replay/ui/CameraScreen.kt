package com.vincentla.action_replay.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var bufferedSec by remember { mutableStateOf(0f) }
    var isRecording by remember { mutableStateOf(false) }
    var playbackUri by remember { mutableStateOf<Uri?>(null) }
    var sessionStartMs by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(0L) }
    var rewindLabel by remember { mutableStateOf("") }
    var rewindVisible by remember { mutableStateOf(false) }

    LaunchedEffect(rewindLabel, rewindVisible) {
        if (rewindVisible) {
            delay(700)
            rewindVisible = false
        }
    }

    val engine = remember {
        CameraEngine(context, object : CameraEngine.Listener {
            override fun onClipSaved(uri: Uri?, label: String) {
                mainHandler.post {
                    if (uri != null) {
                        playbackUri = uri
                        Toast.makeText(context, "Saved $label", Toast.LENGTH_SHORT).show()
                    } else {
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
                mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
            }
        })
    }

    DisposableEffect(Unit) { onDispose { engine.stop() } }

    LaunchedEffect(Unit) {
        while (true) {
            bufferedSec = engine.bufferedSeconds()
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
                                        engine.start(holder.surface)
                                    }
                                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) {}
                                    override fun surfaceDestroyed(holder: SurfaceHolder) { engine.stop() }
                                })
                            }
                        },
                    )

                    playbackUri?.let { uri ->
                        androidx.compose.ui.viewinterop.AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    tag = uri          // fix1: prevent update re-init on first call
                                    setZOrderMediaOverlay(true)   // fix2: overlay above camera SurfaceView
                                    setOnCompletionListener { mainHandler.post { playbackUri = null } }
                                    setOnErrorListener { _, _, _ -> mainHandler.post { playbackUri = null }; true }
                                    setOnPreparedListener { mp -> mp.start() }
                                    setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
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
                                        v.setOnCompletionListener { mainHandler.post { playbackUri = null } }
                                        v.setOnErrorListener { _, _, _ -> mainHandler.post { playbackUri = null }; true }
                                        mp.start()
                                    }
                                    v.setVideoURI(uri)
                                }
                            },
                        )
                    }

                    RewindOverlay(visible = rewindVisible, label = rewindLabel)

                    if (isRecording) {
                        LivePill(modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
                        Timecode(
                            elapsedMs = (nowMs - sessionStartMs).coerceAtLeast(0),
                            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                        )
                    }
                    if (playbackUri != null) {
                        RewindPill(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))
                    }
                }

                FooterLabel(
                    text = "ACTION REPLAY CAM",
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 20.dp),
                )
                FooterLabel(
                    text = "720p · 30FPS",
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 20.dp),
                )
            }

            // ---- Right panel (1/4) --------------------------------------
            ActionReplayPanel(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                isRecording = isRecording,
                bufferedSec = bufferedSec,
                onPlay = {
                    engine.beginSession()
                    sessionStartMs = System.currentTimeMillis()
                    isRecording = true
                },
                onStop = { engine.endSession() },
                onRewind3 = {
                    rewindLabel = "-3s"
                    rewindVisible = true
                    engine.rewindAndSave(3)
                },
                onRewind5 = {
                    rewindLabel = "-5s"
                    rewindVisible = true
                    engine.rewindAndSave(5)
                },
            )
        }

        // Floating help glyph (decorative).
        HelpGlyph(modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
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
            enabled = !isRecording,
            ringColor = Cyan,
            onClick = onPlay,
        ) { drawPlayIcon(it, Cyan) }

        CircleButton(
            label = "STOP",
            enabled = isRecording,
            ringColor = RedLive,
            onClick = onStop,
        ) { drawStopIcon(it, RedLive) }

        CircleButton(
            label = "REWIND\n3 SEC",
            enabled = isRecording && bufferedSec >= 3f,
            ringColor = Purple,
            onClick = onRewind3,
        ) { drawRewindIcon(it, Purple) }

        CircleButton(
            label = "REWIND\n5 SEC",
            enabled = isRecording && bufferedSec >= 5f,
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
    enabled: Boolean,
    ringColor: Color,
    onClick: () -> Unit,
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
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
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
            text = "REWIND",
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

@Composable
private fun HelpGlyph(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .border(1.dp, TextDim, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "?",
            color = TextDim,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
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

package com.vincentla.action_replay.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vincentla.action_replay.camera.CameraEngine
import kotlinx.coroutines.delay

private const val BUFFER_POLL_MS = 200L

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var bufferedSec by remember { mutableStateOf(0f) }
    var isRecording by remember { mutableStateOf(false) }
    var playbackUri by remember { mutableStateOf<Uri?>(null) }

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
                mainHandler.post {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    DisposableEffect(Unit) {
        onDispose { engine.stop() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            bufferedSec = engine.bufferedSeconds()
            delay(BUFFER_POLL_MS)
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Left 3/4 — preview (and inline playback overlay).
        Box(
            modifier = Modifier
                .weight(3f)
                .fillMaxHeight()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            ) {
                AndroidView(
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
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setOnCompletionListener { playbackUri = null }
                                setOnErrorListener { _, _, _ -> playbackUri = null; true }
                                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                                setVideoURI(uri)
                                start()
                            }
                        },
                        update = { view ->
                            view.setVideoURI(uri)
                            view.start()
                        },
                    )
                }
            }
        }

        // Right 1/4 — four square buttons stacked vertically.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SquareButton(
                label = if (isRecording) "REC●" else "Play",
                enabled = !isRecording,
                tint = if (isRecording) Color(0xFF7A0F0F) else Color(0xFF1B5E20),
            ) {
                engine.beginSession()
                isRecording = true
            }
            SquareButton(
                label = "Stop",
                enabled = isRecording,
                tint = Color(0xFF424242),
            ) {
                engine.endSession()
            }
            SquareButton(
                label = "↺ 3s",
                enabled = bufferedSec >= 3f,
                tint = Color(0xFF0D47A1),
            ) {
                engine.rewindAndSave(3)
            }
            SquareButton(
                label = "↺ 5s",
                enabled = bufferedSec >= 5f,
                tint = Color(0xFF4A148C),
            ) {
                engine.rewindAndSave(5)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SquareButton(
    label: String,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .aspectRatio(1f, matchHeightConstraintsFirst = true),
        colors = ButtonDefaults.buttonColors(
            containerColor = tint,
            disabledContainerColor = tint.copy(alpha = 0.3f),
        ),
    ) {
        Text(label)
    }
}

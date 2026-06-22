package com.vincentla.action_replay.ui

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Size
import android.view.TextureView
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// A clip saved this session. Session-scoped only (in-memory) — lost on process death, which
// matches how the rolling buffer / rewind already behave. No MediaStore query, no new permission.
enum class ClipKind(val label: String) { REPLAY("REPLAY"), BATTLE("BATTLE") }
data class SavedClip(val uri: Uri, val kind: ClipKind)

// Chip on the idle live view that opens the reel. Shows a peek of the last-saved clip's frame
// next to the count. Shown only when there's something to show.
@Composable
fun ReelChip(latest: SavedClip, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Panel)
            .border(1.dp, Cyan, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Open the reel — $count saved this session"; role = Role.Button }
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb = rememberThumbnail(latest.uri)
        Box(
            modifier = Modifier
                .height(24.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(2.dp))
                .background(BgBottom),
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp)),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "REEL · $count",
            color = Cyan,
            style = TextStyle(fontFamily = BattleFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.5.sp),
        )
    }
}

// The reel: a modal over everything. A row of numbered clip cards; tap a card to rewatch it in a
// frame-accurate ExoPlayer viewer (self-contained — doesn't touch the live camera/replay pipeline),
// or delete with a confirm. Closes itself if it empties out.
@Composable
fun ReelOverlay(
    clips: List<SavedClip>,
    onClose: () -> Unit,
    onDelete: (SavedClip) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<SavedClip?>(null) }
    var playing by remember { mutableStateOf<SavedClip?>(null) }   // non-null → scrubber viewer is up

    // Deleting the last clip leaves nothing to show — drop back to the live view.
    LaunchedEffect(clips.isEmpty()) { if (clips.isEmpty()) onClose() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BgBottom.copy(alpha = 0.94f))
            // Absorb taps so they don't fall through to the camera buttons beneath the modal —
            // a bare background() doesn't participate in hit testing. Empty handler = swallow only.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        val current = playing
        if (current != null) {
            ScrubberViewer(clip = current, onBack = { playing = null })
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "BATTLE REEL",
                        color = Cyan,
                        style = TextStyle(fontFamily = BattleFont, fontWeight = FontWeight.Black, fontSize = 24.sp, letterSpacing = 2.sp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "${clips.size} THIS SESSION",
                        color = Steel,
                        style = TextStyle(fontFamily = BattleFont, fontSize = 12.sp, letterSpacing = 1.5.sp),
                    )
                    Spacer(Modifier.weight(1f))
                    CloseButton(onClose)
                }

                Spacer(Modifier.height(20.dp))

                LazyRow(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemsIndexed(clips, key = { _, c -> c.uri }) { i, clip ->
                        ReelCard(
                            clip = clip,
                            number = i + 1,     // save order; renumbers after a delete (position-based, honest)
                            onPlay = { playing = clip },
                            onDelete = { pendingDelete = clip },
                        )
                    }
                }
            }
        }

        pendingDelete?.let { clip ->
            // Full-size scrim so a tap can't reach the cards behind the confirm (would otherwise
            // start a rewatch and silently abandon the pending delete).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BgBottom.copy(alpha = 0.6f))
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                DeleteConfirm(
                    onConfirm = { onDelete(clip); pendingDelete = null },
                    onCancel = { pendingDelete = null },
                )
            }
        }
    }
}

@Composable
private fun ReelCard(clip: SavedClip, number: Int, onPlay: () -> Unit, onDelete: () -> Unit) {
    val accent = if (clip.kind == ClipKind.BATTLE) StrikeRed else XBlue
    Column(horizontalAlignment = Alignment.Start) {
        Box(
            modifier = Modifier
                .width(220.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(4.dp))
                .background(Panel)
                .border(1.dp, accent, RoundedCornerShape(4.dp))
                .clickable(onClick = onPlay)
                .semantics { contentDescription = "Rewatch ${clip.kind.label.lowercase()} number $number"; role = Role.Button },
        ) {
            val thumb = rememberThumbnail(clip.uri)
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)),
                )
            }
            // Frame number (top-left) + kind badge (top-right)
            Text(
                text = "%02d".format(number),
                color = Cyan,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                style = TextStyle(fontFamily = BattleFont, fontWeight = FontWeight.Black, fontSize = 18.sp),
            )
            Text(
                text = clip.kind.label,
                color = accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                style = TextStyle(fontFamily = BattleFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.5.sp),
            )
            // Delete affordance (bottom-right)
            Text(
                text = "DELETE",
                color = StrikeRed,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .clickable(onClick = onDelete)
                    .semantics { contentDescription = "Delete ${clip.kind.label.lowercase()} number $number"; role = Role.Button }
                    .padding(8.dp),
                style = TextStyle(fontFamily = BattleFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp),
            )
        }
    }
}

@Composable
private fun DeleteConfirm(onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Panel)
            .border(1.dp, StrikeRed, RoundedCornerShape(4.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "DELETE THIS CLIP?",
            color = StrikeRed,
            style = TextStyle(fontFamily = BattleFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.5.sp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "This removes it from your gallery too.",
            color = Steel,
            style = TextStyle(fontFamily = BattleFont, fontSize = 11.sp),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(text = "CANCEL", color = Steel, onClick = onCancel)
            PillButton(text = "DELETE", color = StrikeRed, onClick = onConfirm)
        }
    }
}

@Composable
private fun PillButton(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .border(1.dp, color, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = color,
            style = TextStyle(fontFamily = BattleFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp),
        )
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .border(1.dp, Steel, RoundedCornerShape(3.dp))
            .clickable(onClick = onClose)
            .semantics { contentDescription = "Close the reel"; role = Role.Button }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = "CLOSE",
            color = Steel,
            style = TextStyle(fontFamily = BattleFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp),
        )
    }
}

// Frame-accurate rewatch viewer (ExoPlayer). Self-contained: owns its player, releases it on
// dispose, and never touches the live camera/replay pipeline. Tap the video to play/pause; drag the
// scrubber; step ±1 frame with -1F/+1F (EXACT seek lands on the precise frame). For all clips.
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ScrubberViewer(clip: SavedClip, onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setSeekParameters(SeekParameters.EXACT)   // frame-accurate, not nearest-keyframe
            setMediaItem(MediaItem.fromUri(clip.uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playingNow: Boolean) { isPlaying = playingNow }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && player.duration > 0L) durationMs = player.duration
                if (state == Player.STATE_ENDED) positionMs = durationMs   // land readout/slider on the end, not ~50ms short
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // Pause on background so a rewatch's audio doesn't keep playing once the app is hidden. Uses the
    // hosting Activity's Lifecycle (lifecycle-runtime-ktx, already a dependency) — no new artifact.
    val lifecycleOwner = remember(context) { context.findLifecycleOwner() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    // Track position while playing; paused/scrub positions come from the seek handlers below.
    LaunchedEffect(isPlaying, scrubbing) {
        while (isPlaying && !scrubbing) {
            positionMs = player.currentPosition
            delay(50)
        }
    }

    val frameMs = 33L   // ~1 frame at the 30fps capture rate
    fun stepBy(deltaMs: Long) {
        player.pause()
        val max = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, max)
        player.seekTo(target)
        positionMs = target
    }

    Box(modifier = Modifier.fillMaxSize().background(BgBottom)) {
        AndroidView(
            factory = { ctx -> TextureView(ctx) },
            // Bind in update (runs after the view is attached) — robust against detach/reattach;
            // ExoPlayer no-ops if the same view is set again.
            update = { player.setVideoTextureView(it) },
            modifier = Modifier.fillMaxSize(),
        )
        // Tap the video to toggle play/pause.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { if (player.isPlaying) player.pause() else player.play() } },
        )
        Box(modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            PillButton(text = "< REEL", color = Cyan, onClick = onBack)
        }
        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
            Slider(
                value = positionMs.coerceIn(0L, if (durationMs > 0L) durationMs else 0L).toFloat(),
                onValueChange = { v -> scrubbing = true; positionMs = v.toLong(); player.seekTo(v.toLong()) },
                onValueChangeFinished = { scrubbing = false },
                valueRange = 0f..(if (durationMs > 0L) durationMs else 1L).toFloat(),
                colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = Steel),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton(text = "-1F", color = Cyan, onClick = { stepBy(-frameMs) })
                Spacer(Modifier.width(10.dp))
                PillButton(text = if (isPlaying) "PAUSE" else "PLAY", color = Cyan, onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                })
                Spacer(Modifier.width(10.dp))
                PillButton(text = "+1F", color = Cyan, onClick = { stepBy(frameMs) })
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${tenths(positionMs)} / ${tenths(durationMs)}",
                    color = Cyan,
                    style = TextStyle(fontFamily = BattleFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp),
                )
            }
        }
    }
}

private fun tenths(ms: Long): String = "%.1fs".format(ms / 1000.0)

// LocalContext may be a ContextWrapper; unwrap to the hosting LifecycleOwner (the Activity).
private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}

// Loads a poster frame off the main thread via MediaStore. No image-loading dependency.
@Composable
private fun rememberThumbnail(uri: Uri): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            // ponytail: loadThumbnail (API 29+) generates a frame on demand; null on failure → the
            // card just shows its Panel fill. Good enough for a session reel.
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(320, 180), null).asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

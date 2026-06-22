package com.vincentla.action_replay.ui

import android.net.Uri
import android.util.Size
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// A clip saved this session. Session-scoped only (in-memory) — lost on process death, which
// matches how the rolling buffer / rewind already behave. No MediaStore query, no new permission.
enum class ClipKind(val label: String) { REPLAY("REPLAY"), BATTLE("BATTLE") }
data class SavedClip(val uri: Uri, val kind: ClipKind)

// Chip on the idle live view that opens the reel. Shown only when there's something to show.
@Composable
fun ReelChip(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Panel)
            .border(1.dp, Cyan, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Open the reel — $count saved this session"; role = Role.Button }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "REEL · $count",
            color = Cyan,
            style = TextStyle(fontFamily = BattleFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.5.sp),
        )
    }
}

// The reel: a modal over everything. A row of numbered clip cards; tap to rewatch (hands off to
// the inline player via onRewatch), or delete with a confirm. Closes itself if it empties out.
@Composable
fun ReelOverlay(
    clips: List<SavedClip>,
    onClose: () -> Unit,
    onRewatch: (SavedClip) -> Unit,
    onDelete: (SavedClip) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<SavedClip?>(null) }

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
                        number = i + 1,         // save order; renumbers after a delete (position-based, honest)
                        onPlay = { onRewatch(clip) },
                        onDelete = { pendingDelete = clip },
                    )
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

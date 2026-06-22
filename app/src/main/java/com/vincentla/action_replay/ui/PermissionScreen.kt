package com.vincentla.action_replay.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ponytail: mirrors the Beyblade X palette in CameraScreen.kt (those are file-private).
// If these ever drift, hoist the shared set into one ui/Theme file. A handful of
// constants isn't worth the cross-file coupling yet.
private val BgTop = Color(0xFF0A1018)
private val BgBottom = Color(0xFF05080F)
private val XBlue = Color(0xFF0091FF)
private val Cyan = Color(0xFF29E0FF)
private val StrikeRed = Color(0xFFFF2D2D)
private val Steel = Color(0xFF5C6B7E)
private val Panel = Color(0xFF101A28)
private val Divider = Color(0xFF1E2C3E)

/**
 * Pre-grant "standby card": the deck is patched but its inputs aren't connected.
 * VIDEO = camera, AUDIO = mic — each shown as a signal channel that lights amber
 * once granted. Shown only while at least one permission is missing.
 *
 * @param permanentlyDenied true when the user denied with "don't ask again" (or twice
 *   on API 30+); the primary action then routes to system Settings instead of re-asking.
 */
@Composable
fun PermissionScreen(
    cameraGranted: Boolean,
    micGranted: Boolean,
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            StandbyTally()

            Spacer(Modifier.height(18.dp))
            Text(
                text = "Camera and mic aren't connected yet",
                color = Cyan,
                style = TextStyle(
                    fontFamily = BattleFont,
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    letterSpacing = 0.5.sp,
                ),
            )

            Spacer(Modifier.height(10.dp))
            Text(
                text = "Action Replay records video and sound to your gallery. Nothing leaves your device.",
                color = Steel,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                ),
            )

            Spacer(Modifier.height(24.dp))
            SignalChannel(label = "VIDEO", ready = cameraGranted)
            Spacer(Modifier.height(8.dp))
            SignalChannel(label = "AUDIO", ready = micGranted)

            Spacer(Modifier.height(24.dp))
            ActionButton(
                text = if (permanentlyDenied) "Open settings" else "Enable camera & mic",
                onClick = if (permanentlyDenied) onOpenSettings else onRequest,
            )

            if (permanentlyDenied) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Turn on Camera and Microphone in settings, then return here.",
                    color = Steel,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun StandbyTally() {
    // Slow ambient pulse — a deck on standby. Single low-amplitude opacity cycle,
    // not a flash; left running under reduced-motion (gentle, non-vestibular).
    val transition = rememberInfiniteTransition(label = "standby")
    val dotAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "tally",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(StrikeRed),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "STANDBY",
            color = StrikeRed,
            style = TextStyle(
                fontFamily = BattleFont,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 3.sp,
            ),
        )
    }
}

/** One broadcast input: label, a level-meter strip, and a READY / NO SIGNAL status. */
@Composable
private fun SignalChannel(label: String, ready: Boolean) {
    val accent = if (ready) Cyan else StrikeRed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .background(Panel)
            .border(1.dp, if (ready) XBlue else Divider, RoundedCornerShape(2.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "$label input ${if (ready) "ready" else "not connected"}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(7.dp).clip(CircleShape).background(accent),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = Steel,
            style = TextStyle(
                fontFamily = BattleFont,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
            ),
        )
        Spacer(Modifier.width(14.dp))
        LevelMeter(lit = ready)
        Spacer(Modifier.weight(1f))
        Text(
            text = if (ready) "READY" else "NO SIGNAL",
            color = accent,
            style = TextStyle(
                fontFamily = BattleFont,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
            ),
        )
    }
}

/** Input level strip — all bars lit amber when the signal is present, else dim/steel. */
@Composable
private fun LevelMeter(lit: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        // Stepped heights so it reads as a meter, not a barcode.
        listOf(6, 10, 14, 18, 12).forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (lit) Cyan else Steel.copy(alpha = 0.3f)),
            )
        }
    }
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(Panel)
            .border(1.5.dp, XBlue, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Cyan,
            style = TextStyle(
                fontFamily = BattleFont,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 1.5.sp,
            ),
        )
    }
}

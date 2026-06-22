package com.vincentla.bbrec.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.vincentla.bbrec.R

// "Beyblade X" palette — shared by every screen (was duplicated per-file; hoisted here once a
// third consumer appeared). Electric blue + cyan on ink, strike-red alarm, rare volt accent.
internal val BgTop = Color(0xFF0A1018)
internal val BgBottom = Color(0xFF05080F)
internal val XBlue = Color(0xFF0091FF)
internal val Cyan = Color(0xFF29E0FF)
internal val StrikeRed = Color(0xFFFF2D2D)
internal val Volt = Color(0xFFFFE600)
internal val Steel = Color(0xFF7C8A9E)   // muted label grey; lightened from #5C6B7E to clear WCAG AA (~4.5:1) for small text on ink
internal val Panel = Color(0xFF101A28)
internal val Divider = Color(0xFF1E2C3E)

// True when the user has turned animations off system-wide (Developer Options / accessibility).
// Used to drop ambient *looping* motion (spin-gauge orbit, standby pulse) while keeping
// functional one-shot transitions. Read once at composition; the setting changes rarely.
internal fun isReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

// VT323 — pixel/terminal display face (SIL OFL). Reads as a battle-computer readout, which
// suits the "3, 2, 1, GO SHOOT" countdown and the HUD. Single weight (regular), no italic —
// pixel fonts don't slant, so styles using this drop fontStyle = Italic.
//
// REQUIRES the font file at: app/src/main/res/font/vt323.ttf
// Download VT323-Regular.ttf from fonts.google.com/specimen/VT323 (free, OFL), rename to
// vt323.ttf (resource names must be lowercase), drop it in res/font/. Without the file the
// build fails on R.font.vt323 — add it on the build machine before assembling.
val BattleFont = FontFamily(Font(R.font.vt323))

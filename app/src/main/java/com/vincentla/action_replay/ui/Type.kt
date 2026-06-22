package com.vincentla.action_replay.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.vincentla.action_replay.R

// VT323 — pixel/terminal display face (SIL OFL). Reads as a battle-computer readout, which
// suits the "3, 2, 1, GO SHOOT" countdown and the HUD. Single weight (regular), no italic —
// pixel fonts don't slant, so styles using this drop fontStyle = Italic.
//
// REQUIRES the font file at: app/src/main/res/font/vt323.ttf
// Download VT323-Regular.ttf from fonts.google.com/specimen/VT323 (free, OFL), rename to
// vt323.ttf (resource names must be lowercase), drop it in res/font/. Without the file the
// build fails on R.font.vt323 — add it on the build machine before assembling.
val BattleFont = FontFamily(Font(R.font.vt323))

package com.xiaozhangben.ledger.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens, light and dark.
 *
 * One accent colour only, reserved for "money" and for state (warning / over
 * budget). Nothing here is decorative — per the design spec, colour must carry
 * meaning, because a ledger where everything is colourful makes an overspend
 * invisible.
 */

// Light -------------------------------------------------------------------------
val LightPrimary = Color(0xFF0E7C66)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFC9EADF)
val LightOnPrimaryContainer = Color(0xFF06382D)
val LightBackground = Color(0xFFF7F8F7)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEDF1EF)
val LightOnSurface = Color(0xFF141A18)
val LightOnSurfaceVariant = Color(0xFF5A6360)
val LightOutline = Color(0xFFD5DCD9)

// Dark --------------------------------------------------------------------------
val DarkPrimary = Color(0xFF4FBF9F)
val DarkOnPrimary = Color(0xFF00382C)
val DarkPrimaryContainer = Color(0xFF1E463B)
val DarkOnPrimaryContainer = Color(0xFFB8E6D7)
val DarkBackground = Color(0xFF101412)
val DarkSurface = Color(0xFF181D1B)
val DarkSurfaceVariant = Color(0xFF232A27)
val DarkOnSurface = Color(0xFFE6EAE8)
val DarkOnSurfaceVariant = Color(0xFF9AA5A1)
val DarkOutline = Color(0xFF2E3734)

// Semantic states (identical in both themes, deliberately) ----------------------
val IncomeGreen = Color(0xFF16A34A)
val WarnAmber = Color(0xFFF59E0B)
val DangerRed = Color(0xFFDC2626)

// NOTE: the alert bands themselves live in the domain as
// `domain.budget.AlertLevel`, so the 70/90/over thresholds exist in exactly one
// place. The UI only maps a band to a colour (see `bandColor`).

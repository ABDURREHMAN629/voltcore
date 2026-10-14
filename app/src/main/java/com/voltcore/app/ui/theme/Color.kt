package com.voltcore.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * VoltCore's palette.
 *
 * Dark-first: this app is looked at in the dark, on a nightstand, while the phone charges.
 * Surfaces are deep and desaturated so the accents — which carry the actual meaning — are
 * the only things that glow.
 *
 * Two rules keep the colour honest, and both were checked with a palette validator rather
 * than by eye:
 *
 *  1. **Charging speed is ordinal, so it gets a sequential ramp**: one hue, dim to
 *     brilliant. An earlier draft used a blue-to-yellow rainbow, in which "Slow" and
 *     "Normal" landed 6.7 Delta E apart — indistinguishable even with full colour vision,
 *     never mind colour blindness. Brightness now encodes speed, which is readable at a
 *     glance and degrades gracefully.
 *  2. **Status colours are reserved.** Green, amber and red mean good / warning / critical
 *     and are never reused to mean "fast". That is why the speed ramp stays inside one
 *     hue: it leaves the semantic colours free to actually mean something.
 *
 * Colour is never the only channel — every speed and status is rendered with its label
 * beside it.
 */

val Ink900 = Color(0xFF050A14)
val Ink800 = Color(0xFF0A1020)
val Ink700 = Color(0xFF111A2E)
val Ink600 = Color(0xFF1A2540)
val Ink500 = Color(0xFF243152)

val Paper50 = Color(0xFFF6F7FB)
val Paper100 = Color(0xFFECEFF6)
val Paper200 = Color(0xFFDDE3ED)
val Paper300 = Color(0xFFC4CDDD)

val Cyan300 = Color(0xFF67E8F9)
val Cyan400 = Color(0xFF22D3EE)
val Cyan500 = Color(0xFF06B6D4)
val Cyan600 = Color(0xFF0891B2)
val Cyan700 = Color(0xFF0E7490)

val Indigo300 = Color(0xFFA5B4FC)
val Indigo400 = Color(0xFF818CF8)
val Indigo500 = Color(0xFF6366F1)
val Indigo700 = Color(0xFF4338CA)

val Violet400 = Color(0xFFA78BFA)
val Violet500 = Color(0xFF8B5CF6)

val SpeedTrickleDark = Cyan700
val SpeedSlowDark = Cyan600
val SpeedNormalDark = Cyan500
val SpeedFastDark = Cyan400
val SpeedUltraDark = Cyan300

val SpeedTrickleLight = Color(0xFF7DD3E8)
val SpeedSlowLight = Color(0xFF38BDF8)
val SpeedNormalLight = Color(0xFF0891B2)
val SpeedFastLight = Color(0xFF0E7490)
val SpeedUltraLight = Color(0xFF155E75)

val GoodDark = Color(0xFF34D399)
val WarnDark = Color(0xFFFBBF24)
val CriticalDark = Color(0xFFEF4444)

val GoodLight = Color(0xFF047857)
val WarnLight = Color(0xFFCA8A04)
val CriticalLight = Color(0xFFBE123C)

val DrainDark = Color(0xFFFB923C)
val DrainLight = Color(0xFFC2410C)

val TempColdDark = Color(0xFF60A5FA)
val TempMildDark = Color(0xFF94A3B8)
val TempWarmDark = Color(0xFFFBBF24)
val TempHotDark = Color(0xFFEF4444)

val TempColdLight = Color(0xFF2563EB)
val TempMildLight = Color(0xFF64748B)
val TempWarmLight = Color(0xFFCA8A04)
val TempHotLight = Color(0xFFBE123C)

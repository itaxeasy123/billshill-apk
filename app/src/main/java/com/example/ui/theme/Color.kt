package com.example.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * THE single place every colour in this app is declared.
 *
 * Nothing outside this file should contain a colour literal — no `Color(0xFF…)`, no
 * `Color.White`, no `Color.Gray`. Change a value here and it changes everywhere it is
 * used. That is the whole point of the file: before this existed the same colour had been
 * retyped by hand in a dozen places, and several had drifted a shade or two apart while
 * nobody noticed (see NEAR-DUPLICATES at the bottom).
 *
 * `Color.Transparent` is the one deliberate exception left in the screens: it means
 * "draw nothing", not "use the brand's transparent", so centralising it would be noise.
 */

// ═══════════════════════════════════════════════════════════════════════════════
//  BRAND PALETTE — the purple identity. Change these to rebrand the app.
// ═══════════════════════════════════════════════════════════════════════════════

val ElectricPurple = Color(0xFF651FFF)
val RoyalPurplePrimary = Color(0xFF651FFF)
val DeepPurpleSecondary = Color(0xFF7C4DFF)
val DarkPurpleVariant = Color(0xFF4A00B0)
val LavenderContainer = Color(0xFFF0E6FF)
val SecondaryContainer = Color(0xFFE8DDFF)
val SurfaceVariant = Color(0xFFF4EEFF)

val RoyalPurpleDarkPrimary = Color(0xFFB388FF)
val DeepPurpleDarkSecondary = Color(0xFF8C9EFF)

// ═══════════════════════════════════════════════════════════════════════════════
//  ACCOUNTING SEMANTICS — money in, money out, destructive.
// ═══════════════════════════════════════════════════════════════════════════════

val AccountingGreen = Color(0xFF2E7D32)
val AccountingRed = Color(0xFFD32F2F) // Red for Purchases/Outflow
val HardRedDelete = Color(0xFFC62828) // Hard red for Delete actions
val AmberGold = Color(0xFFFFA000)
val AmberContainer = Color(0xFFFFF8E1)

// ═══════════════════════════════════════════════════════════════════════════════
//  SURFACES & BODY TEXT — consumed by the light/dark schemes in Theme.kt.
// ═══════════════════════════════════════════════════════════════════════════════

val SurfaceLight = Color(0xFFFFFFFF)
val BackgroundLight = Color(0xFFF8F5FF)
val TextPrimaryLight = Color(0xFF1C1B1F)

val SurfaceDark = Color(0xFF181124)
val BackgroundDark = Color(0xFF120C1D)
val TextPrimaryDark = Color(0xFFE6E0E9)

// ═══════════════════════════════════════════════════════════════════════════════
//  NEUTRALS
//
//  Split by ROLE rather than by shade, so a change lands only where it is meant to.
//  Several currently share a value — that is intentional, not redundancy: they are
//  separate knobs that happen to be set to the same setting today. Collapsing them
//  would mean you could no longer restyle chip labels without also restyling the
//  printed invoice.
// ═══════════════════════════════════════════════════════════════════════════════

/** Text and icons sitting ON a filled brand/accent container (purple, green, red). */
val OnAccent = Color.White

/** A genuinely white surface — the printed-invoice preview is white paper. */
val PaperSurface = Color.White

/** Text and icons on the dark console/import panel. */
val OnDarkPanel = Color.White

/** Data-point markers drawn on chart canvases. */
val ChartMarker = Color.White

/** Ink on a light surface — printed invoice text, labels on pale chips. */
val InkBlack = Color.Black

/** Label sitting on the neon console accent. */
val OnNeon = Color.Black

/** Full-screen scrim behind the import/scanner panel. */
val ScannerScrim = Color.Black

/** Secondary text, in three weights from most to least prominent. */
val MutedTextStrong = Color.DarkGray
val MutedText = Color.Gray
val MutedTextSoft = Color.LightGray

/** Hairlines: unfocused field outlines, chart axes/baselines, dividers. */
val SubtleBorder = Color.Gray

/**
 * Chart gridlines — lighter than [SubtleBorder], which is the axis/baseline.
 *
 * These are two different weights on purpose: the baseline is meant to read as a line,
 * the gridlines as a hint behind the data. Collapsing them onto one constant is what
 * darkened the gridlines during this refactor, so they stay separate.
 */
val ChartGridline = Color.LightGray

// ═══════════════════════════════════════════════════════════════════════════════
//  CONSOLE PANEL — the dark, neon-accented invoice-import and JSON-debug surfaces.
//
//  Deliberately fixed rather than theme-derived: this panel is dark in light mode and
//  dark in dark mode, the way a terminal is. Restyle it here, in one place.
// ═══════════════════════════════════════════════════════════════════════════════

val ConsoleAccent = Color(0xFF00FF88)
val ConsolePanel = Color(0xFF1E1E28)
val ConsoleBackdrop = Color(0xFF0F0F14)

// ═══════════════════════════════════════════════════════════════════════════════
//  WARNING & VALIDATION — import conflicts, filing deadlines, duplicate rows.
// ═══════════════════════════════════════════════════════════════════════════════

val WarnAmberText = Color(0xFFD97706)
val WarnAmberDeadline = Color(0xFFB76E00)
val WarnAmberStatus = Color(0xFFFFC107)
val WarnYellowWash = Color(0xFFFEF3C7)
val WarnRedWash = Color(0xFFFEE2E2)

// ═══════════════════════════════════════════════════════════════════════════════
//  PRINTED INVOICE — the document preview, which imitates paper and stays light.
// ═══════════════════════════════════════════════════════════════════════════════

val InvoiceHeaderWash = Color(0xFFF3EDF7)
val InvoiceTaxWash = Color(0xFFFAF9FD)
val InvoiceTotalWash = Color(0xFFEADDFF)
val InvoiceTotalInk = Color(0xFF21005D)

// ═══════════════════════════════════════════════════════════════════════════════
//  CHARTS — a categorical sequence. Order matters; slices are assigned by index.
// ═══════════════════════════════════════════════════════════════════════════════

val ChartSlice1 = Color(0xFFFF9800)
val ChartSlice2 = Color(0xFF00BCD4)
val ChartSlice3 = Color(0xFFE91E63)
val ChartSlice4 = Color(0xFF4CAF50)
val ChartSlice5 = Color(0xFF9E9E9E)

/** Signature stroke on the signature pad. */
val SignatureInk = Color(0xFF1B1B2F)

// ═══════════════════════════════════════════════════════════════════════════════
//  NEAR-DUPLICATES — known drift, left as-is because collapsing them WOULD change
//  what renders. Each pair below is almost certainly meant to be one colour; they
//  differ only because someone eyeballed a shade instead of importing the constant.
//  Collapsing any pair is now a one-line edit in this file:
//
//    InvoiceTotalWash  #EADDFF  vs  SecondaryContainer  #E8DDFF   (imperceptible)
//    InvoiceTaxWash    #FAF9FD  vs  BackgroundLight     #F8F5FF
//    ChartSlice1       #FF9800  vs  AmberGold           #FFA000
//    InvoiceHeaderWash #F3EDF7  vs  SurfaceVariant      #F4EEFF
//    ConsolePanel      #1E1E28  vs  TextPrimaryLight    #1C1B1F
//    ConsoleBackdrop   #0F0F14  vs  BackgroundDark      #120C1D
//
//  Four separate ambers also mean "warning": WarnAmberText, WarnAmberDeadline,
//  WarnAmberStatus and AmberGold. ElectricPurple and RoyalPurplePrimary are already
//  byte-identical and are both in active use.
// ═══════════════════════════════════════════════════════════════════════════════

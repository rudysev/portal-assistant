package com.portal.assistant.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.portal.assistant.R

// A deliberate, bundled typeface so the brand renders identically on every Portal model — never at the
// mercy of whatever system font a given device/OS build ships. Inter is a humanist sans that stays crisp
// at the large sizes this countertop UI uses and reads cleanly from across the room.
// Licensed under the SIL Open Font License 1.1 — see licenses/Inter-OFL.txt.
val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

// Every Material 3 type role re-pointed at Inter. Material components (Button, text fields, menus) read
// their styles from here; bare Text composables inherit Inter via LocalTextStyle in AssistantTheme. The
// small roles are bumped above Material's phone defaults so component text — text-field input + floating
// labels (bodyLarge/bodySmall), button labels (labelLarge) — stays legible from across the room. Line
// heights keep their defaults (each remains ≥ the bumped size, so nothing clips). The large display/headline
// roles aren't used by components here, so they keep Material's sizes.
private val default = Typography()
val AssistantTypography = Typography(
    displayLarge = default.displayLarge.copy(fontFamily = Inter),
    displayMedium = default.displayMedium.copy(fontFamily = Inter),
    displaySmall = default.displaySmall.copy(fontFamily = Inter),
    headlineLarge = default.headlineLarge.copy(fontFamily = Inter),
    headlineMedium = default.headlineMedium.copy(fontFamily = Inter),
    headlineSmall = default.headlineSmall.copy(fontFamily = Inter),
    titleLarge = default.titleLarge.copy(fontFamily = Inter),
    titleMedium = default.titleMedium.copy(fontFamily = Inter),
    titleSmall = default.titleSmall.copy(fontFamily = Inter),
    bodyLarge = default.bodyLarge.copy(fontFamily = Inter, fontSize = 18.sp), // text-field input + resting label
    bodyMedium = default.bodyMedium.copy(fontFamily = Inter, fontSize = 16.sp),
    bodySmall = default.bodySmall.copy(fontFamily = Inter, fontSize = 14.sp), // floating field label
    labelLarge = default.labelLarge.copy(fontFamily = Inter, fontSize = 16.sp), // button text
    labelMedium = default.labelMedium.copy(fontFamily = Inter),
    labelSmall = default.labelSmall.copy(fontFamily = Inter),
)

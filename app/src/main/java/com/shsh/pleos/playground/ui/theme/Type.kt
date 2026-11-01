package com.shsh.pleos.playground.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Use the device Korean font until a redistributable Noto Sans KR asset is supplied.
val Typography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 70.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 62.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 48.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 44.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 34.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 42.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 42.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 38.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 34.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 36.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 30.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 32.sp)
)

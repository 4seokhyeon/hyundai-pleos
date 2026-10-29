package com.shsh.pleos.playground.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Use the device Korean font until a redistributable Noto Sans KR asset is supplied.
val Typography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 56.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 46.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 38.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 36.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp),
    bodyLarge = TextStyle(fontSize = 24.sp, lineHeight = 34.sp),
    bodyMedium = TextStyle(fontSize = 20.sp, lineHeight = 30.sp),
    bodySmall = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp)
)

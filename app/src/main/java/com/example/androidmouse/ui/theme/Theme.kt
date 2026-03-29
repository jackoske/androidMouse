package com.example.androidmouse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

// Vibrant dark theme — bold accent colors, high contrast
private val DarkColors = darkColorScheme(
    primary            = Color(0xFF00E5FF),   // electric cyan
    onPrimary          = Color(0xFF003738),
    primaryContainer   = Color(0xFF00363A),
    onPrimaryContainer = Color(0xFF6FF7FF),
    secondary          = Color(0xFFB388FF),   // vibrant purple
    onSecondary        = Color(0xFF1F0047),
    secondaryContainer = Color(0xFF2D1B69),
    onSecondaryContainer = Color(0xFFDBC2FF),
    tertiary           = Color(0xFFFFAB40),   // bright amber
    onTertiary         = Color(0xFF3E2700),
    tertiaryContainer  = Color(0xFF5C3D00),
    onTertiaryContainer = Color(0xFFFFDDB3),
    background         = Color(0xFF0A0E12),   // deep black-blue
    onBackground       = Color(0xFFECEFF1),
    surface            = Color(0xFF111921),   // slightly lifted
    onSurface          = Color(0xFFECEFF1),
    surfaceVariant     = Color(0xFF1A2633),   // block backgrounds
    onSurfaceVariant   = Color(0xFFB0BEC5),
    outline            = Color(0xFF37474F),
    outlineVariant     = Color(0xFF1E2D3A),
    error              = Color(0xFFFF1744),
    inverseSurface     = Color(0xFFECEFF1),
    inverseOnSurface   = Color(0xFF0A0E12),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 0.8.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 0.8.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
    ),
)

@Composable
fun AndroidMouseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content,
    )
}

package com.visualassistant.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightPalette = lightColorScheme(
    primary = Color(0xFF174EA6), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E5DC), onPrimaryContainer = Color(0xFF252921),
    secondary = Color(0xFF28665D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3F3EF), onSecondaryContainer = Color(0xFF173F39),
    tertiary = Color(0xFF805600), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEBC8), onTertiaryContainer = Color(0xFF4B3100),
    background = Color(0xFFFAF9F6), onBackground = Color(0xFF242620),
    surface = Color(0xFFFFFEFB), onSurface = Color(0xFF242620),
    surfaceVariant = Color(0xFFF0EEE7), onSurfaceVariant = Color(0xFF44483F),
    outline = Color(0xFF687A92),
)

private val DarkPalette = darkColorScheme(
    primary = Color(0xFFB2CAFF), onPrimary = Color(0xFF002C70),
    primaryContainer = Color(0xFF174078), onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFF9DD6C9), onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF204F48), onSecondaryContainer = Color(0xFFC3EFE4),
    tertiary = Color(0xFFFFD58B), onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF604000), onTertiaryContainer = Color(0xFFFFEBC8),
    background = Color(0xFF1C1D1A), onBackground = Color(0xFFE8E8DF),
    surface = Color(0xFF242520), onSurface = Color(0xFFE8E8DF),
    surfaceVariant = Color(0xFF3C3F37), onSurfaceVariant = Color(0xFFD7DACF),
    outline = Color(0xFF9DAFC6),
)

@Composable
internal fun VisualAssistantTheme(content: @Composable () -> Unit) {
    val baseline = Typography()
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkPalette else LightPalette,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(36.dp),
        ),
        typography = baseline.copy(
            headlineLarge = baseline.headlineLarge.copy(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
            headlineMedium = baseline.headlineMedium.copy(fontSize = 30.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = baseline.titleLarge.copy(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = baseline.bodyLarge.copy(fontSize = 20.sp, lineHeight = 29.sp),
            bodyMedium = baseline.bodyMedium.copy(fontSize = 18.sp, lineHeight = 26.sp),
        ),
        content = content,
    )
}

package com.dawnyew.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

internal object FinanceColors {
    val brand = Color(0xFF00C4B3)
    val expense = Color(0xDCFF5666)
    val income = Color(0xFF00C4B3)
    val transfer = Color(0xFF45CAE6)
}

private val Light = lightColorScheme(
    primary = Color(0xFF00C4B3), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF8F4), onPrimaryContainer = Color(0xFF004D47),
    secondary = Color(0xFF506B72), secondaryContainer = Color(0xFFE8F0F5), onSecondaryContainer = Color(0xFF263E47),
    tertiary = Color(0xFFD24966), tertiaryContainer = Color(0xFFFFE5EC), onTertiaryContainer = Color(0xFF681D31),
    background = Color(0xFFF2F5F9), onBackground = Color(0xFF000000),
    surface = Color.White, onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF2F5F9), onSurfaceVariant = Color(0xFF999999),
    outline = Color(0xFFC2C2C2), outlineVariant = Color(0xFFE5EBEF),
    surfaceContainer = Color.White, surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color(0xFFEBF1F5), surfaceContainerHighest = Color(0xFFE5EDF2),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF61DACC), onPrimary = Color(0xFF003B35),
    primaryContainer = Color(0xFF07514A), onPrimaryContainer = Color(0xFFAEF4E9),
    secondary = Color(0xFFA9C8D0), secondaryContainer = Color(0xFF2A4149), onSecondaryContainer = Color(0xFFD7ECF2),
    tertiary = Color(0xFFFF9CB1), tertiaryContainer = Color(0xFF663042), onTertiaryContainer = Color(0xFFFFD9E2),
    background = Color(0xFF10191E), onBackground = Color(0xFFE3EDF1),
    surface = Color(0xFF1A252B), onSurface = Color(0xFFE3EDF1),
    surfaceVariant = Color(0xFF253239), onSurfaceVariant = Color(0xFFADBBC3),
    outline = Color(0xFF81919A), outlineVariant = Color(0xFF34454E),
    surfaceContainer = Color(0xFF1A252B), surfaceContainerLow = Color(0xFF1A252B),
    surfaceContainerHigh = Color(0xFF243239), surfaceContainerHighest = Color(0xFF2C3B43),
)

@Composable fun DawnYewTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = Typography(
            displaySmall = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
            headlineLarge = TextStyle(fontSize = 26.sp, lineHeight = 34.sp),
            headlineMedium = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
            headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
            titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
            titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
            titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
            bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
            labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
            labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp),
        ),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(16.dp)),
        content = content,
    )
}

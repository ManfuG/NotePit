package io.notepit.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3157D5), onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE5FF), onPrimaryContainer = Color(0xFF10256A),
    secondary = Color(0xFFA13E67), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E7), onSecondaryContainer = Color(0xFF46152B),
    tertiary = Color(0xFF5E5A7D),
    background = Color(0xFFFAFAFF), onBackground = Color(0xFF191C28),
    surface = Color(0xFFFAFAFF), onSurface = Color(0xFF191C28),
    surfaceVariant = Color(0xFFE4E5EF), onSurfaceVariant = Color(0xFF454654),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F4FC),
    surfaceContainer = Color(0xFFEEEFF9), surfaceContainerHigh = Color(0xFFE8EAF5),
    surfaceContainerHighest = Color(0xFFE2E5F1), surfaceTint = Color(0xFF3157D5),
    outline = Color(0xFF757684), error = Color(0xFFBA1A1A),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9C4FF), onPrimary = Color(0xFF002581),
    primaryContainer = Color(0xFF2541A8), onPrimaryContainer = Color(0xFFDFE5FF),
    secondary = Color(0xFFFFB0CE), onSecondary = Color(0xFF621E3B),
    secondaryContainer = Color(0xFF822F51), onSecondaryContainer = Color(0xFFFFD9E7),
    tertiary = Color(0xFFC7C1EA),
    background = Color(0xFF11141F), onBackground = Color(0xFFE3E5F3),
    surface = Color(0xFF1B2030), onSurface = Color(0xFFE3E5F3),
    surfaceVariant = Color(0xFF414655), onSurfaceVariant = Color(0xFFC4C6D6),
    surfaceContainerLowest = Color(0xFF0D101A), surfaceContainerLow = Color(0xFF171C2B),
    surfaceContainer = Color(0xFF1B2030), surfaceContainerHigh = Color(0xFF252B3E),
    surfaceContainerHighest = Color(0xFF30374B), surfaceTint = Color(0xFFB9C4FF),
    outline = Color(0xFF8E92A3), error = Color(0xFFFFB4AB),
)

@Composable
fun NotePitTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

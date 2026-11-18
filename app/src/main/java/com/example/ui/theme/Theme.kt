package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val SolDarkColorScheme = darkColorScheme(
    primary = SolCyan,
    onPrimary = SolOnCyan,
    primaryContainer = SolCyanContainer,
    onPrimaryContainer = SolCyanBright,
    secondary = SolAmber,
    onSecondary = SolOnAmber,
    secondaryContainer = SolAmberContainer,
    onSecondaryContainer = SolAmber,
    tertiary = SolEmerald,
    onTertiary = SolMidnight,
    tertiaryContainer = SolEmeraldContainer,
    onTertiaryContainer = SolEmerald,
    background = SolMidnight,
    onBackground = SolTextPrimary,
    surface = SolSurface,
    onSurface = SolTextPrimary,
    surfaceVariant = SolSurfaceElevated,
    onSurfaceVariant = SolTextSecondary,
    surfaceContainerLow = SolDeepNavy,
    surfaceContainer = SolSurface,
    surfaceContainerHigh = SolSurfaceElevated,
    surfaceContainerHighest = SolSurfaceHighest,
    outline = SolOutline,
    outlineVariant = SolOutline.copy(alpha = 0.45f),
    error = SolCoral,
    onError = SolMidnight,
    errorContainer = SolCoralContainer,
    onErrorContainer = SolCoral,
)

private val SolLightColorScheme = lightColorScheme(
    primary = SolLightPrimary,
    onPrimary = SolLightSurface,
    primaryContainer = SolLightPrimaryContainer,
    onPrimaryContainer = SolLightTextPrimary,
    secondary = SolAmber,
    onSecondary = SolOnAmber,
    secondaryContainer = SolAmberContainer.copy(alpha = 0.14f),
    onSecondaryContainer = SolOnAmber,
    tertiary = SolEmerald,
    onTertiary = SolLightSurface,
    background = SolLightBg,
    onBackground = SolLightTextPrimary,
    surface = SolLightSurface,
    onSurface = SolLightTextPrimary,
    surfaceVariant = SolLightSurfaceVariant,
    onSurfaceVariant = SolLightTextSecondary,
    surfaceContainerLow = SolLightBg,
    surfaceContainer = SolLightSurface,
    surfaceContainerHigh = SolLightSurfaceVariant,
    outline = SolLightOutline,
    outlineVariant = SolLightOutline.copy(alpha = 0.5f),
    error = SolCoral,
    onError = SolLightSurface,
)

val SolShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SolTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) SolDarkColorScheme else SolLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = SolTypography,
        shapes = SolShapes,
        content = content,
    )
}

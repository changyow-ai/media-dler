package com.changyow.mediadler.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC3EDE2),
    onPrimaryContainer = Color(0xFF002D25),
    secondary = Color(0xFF4B635C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1E8DE),
    onSecondaryContainer = Color(0xFF102E25),
    tertiary = Color(0xFF3F6376),
    tertiaryContainer = Color(0xFFC4E7FC),
    onTertiaryContainer = Color(0xFF001E2B),
    background = Color(0xFFF7F9F7),
    onBackground = Color(0xFF17201D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17201D),
    surfaceVariant = Color(0xFFE3EBE6),
    onSurfaceVariant = Color(0xFF4D5B55),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F5F1),
    surfaceContainer = Color(0xFFEAF0EB),
    surfaceContainerHigh = Color(0xFFE4EBE6),
    surfaceContainerHighest = Color(0xFFDDE5DF),
    outline = Color(0xFF75827A),
    outlineVariant = Color(0xFFD8E1DA),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005044),
    onPrimaryContainer = Color(0xFF9FF2DC),
    secondary = Color(0xFFB2CCC1),
    onSecondary = Color(0xFF1E352D),
    secondaryContainer = Color(0xFF354C43),
    onSecondaryContainer = Color(0xFFCEE8DC),
    tertiary = Color(0xFF9FCBDF),
    tertiaryContainer = Color(0xFF234B5C),
    onTertiaryContainer = Color(0xFFC4E7FC),
    background = Color(0xFF101713),
    onBackground = Color(0xFFE1E9E2),
    surface = Color(0xFF151E18),
    onSurface = Color(0xFFE1E9E2),
    surfaceVariant = Color(0xFF3E4B43),
    onSurfaceVariant = Color(0xFFBECAC1),
    surfaceContainerLowest = Color(0xFF0B120E),
    surfaceContainerLow = Color(0xFF19221C),
    surfaceContainer = Color(0xFF1D2720),
    surfaceContainerHigh = Color(0xFF273129),
    surfaceContainerHighest = Color(0xFF323C34),
    outline = Color(0xFF89978C),
    outlineVariant = Color(0xFF3E4B43),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun MediaDlerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

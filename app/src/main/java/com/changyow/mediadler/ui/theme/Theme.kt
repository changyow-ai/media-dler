package com.changyow.mediadler.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

@Composable
fun MediaDlerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(primary = SkyDark)
    } else {
        lightColorScheme(primary = Sky)
    }
    MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
}

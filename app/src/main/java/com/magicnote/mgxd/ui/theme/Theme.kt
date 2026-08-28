package com.magicnote.mgxd.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Primary80,
    onPrimary = Color(0xFF2A1F6E),
    primaryContainer = PrimaryContainer80,
    onPrimaryContainer = Color(0xFFE8DEFF),
    secondary = Secondary80,
    tertiary = Tertiary80,
    background = BackgroundDark,
    surface = SurfaceDark
)

private val LightColorScheme = lightColorScheme(
    primary = Primary40,
    onPrimary = Color.White,
    primaryContainer = PrimaryContainer40,
    onPrimaryContainer = Color(0xFF1E1256),
    secondary = Secondary40,
    tertiary = Tertiary40,
    background = BackgroundLight,
    surface = SurfaceLight
)

@Composable
fun LinxiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
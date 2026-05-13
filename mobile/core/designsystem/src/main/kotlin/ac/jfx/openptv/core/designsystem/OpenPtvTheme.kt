package ac.jfx.openptv.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 colour scheme entry point.
 *
 *  - Android 12+ (`Build.VERSION_CODES.S`): use dynamic colour sourced from the user's wallpaper.
 *  - Below Android 12: fall back to a Material 3 default palette. ReadYou's tonal-palette port
 *    lands in a follow-up issue; until then, the stock M3 defaults are a deliberate placeholder
 *    so non-dynamic devices feel "Material 3", not "styled-by-accident".
 */
@Composable
fun OpenPtvTheme(
    themeMode: ThemeMode = LocalThemeMode.current,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val useDark =
        when (themeMode) {
            ThemeMode.System -> systemDark
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }

    val colorScheme: ColorScheme =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ctx = LocalContext.current
            if (useDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } else {
            if (useDark) darkColorScheme() else lightColorScheme()
        }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

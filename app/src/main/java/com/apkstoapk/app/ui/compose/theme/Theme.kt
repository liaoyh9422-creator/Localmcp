package com.apkstoapk.app.ui.compose.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MyToolsDarkColors = darkColorScheme(
    primary = MdPrimary,
    onPrimary = MdOnPrimary,
    primaryContainer = MdPrimaryContainer,
    onPrimaryContainer = MdOnPrimaryContainer,
    secondary = MdSecondary,
    onSecondary = MdOnSecondary,
    secondaryContainer = MdSecondaryContainer,
    background = MdBg,
    onBackground = MdTextPrimary,
    surface = MdSurface,
    onSurface = MdTextPrimary,
    onSurfaceVariant = MdTextSecondary,
    outline = MdStroke,
    error = MdError,
    surfaceVariant = MdSurface2,
)

@Composable
fun MyToolsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MyToolsDarkColors,
        content = content
    )
}

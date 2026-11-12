package eu.wenjun.notes

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF1C1C1E)
val Panel = Color(0xFF161618)
val Panel2 = Color(0xFF26262A)
val Ink = Color(0xFFF5F5F7)
val Ink2 = Color(0xFFC2C2C9)
val Muted = Color(0xFF8C8C94)
val Line = Color(0xFF38383E)
val Amber = Color(0xFFE0A94A)
val Danger = Color(0xFFF08C82)

@Composable
fun NotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amber,
            onPrimary = Color(0xFF1A1409),
            background = Bg,
            onBackground = Ink,
            surface = Panel,
            onSurface = Ink,
            surfaceVariant = Panel2,
            onSurfaceVariant = Ink2,
            outline = Line,
            error = Danger
        ),
        typography = Typography(),
        content = content
    )
}

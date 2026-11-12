package eu.wenjun.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 外观：默认浅色（米白底 + 白卡片 + 翠绿强调），可切深色（深墨绿石墨底 + 亮翠绿强调）。
 * 两套共用一套语义色名，切换时只换值，所有界面自动跟着变。
 */
object Look {
    var dark by mutableStateOf(false)
}

private val LightBg = Color(0xFFF6F8F5)
private val LightPanel = Color(0xFFFFFFFF)
private val LightPanel2 = Color(0xFFEDF2EE)
private val LightInk = Color(0xFF16211C)
private val LightInk2 = Color(0xFF4B5852)
private val LightMuted = Color(0xFF85928B)
private val LightLine = Color(0xFFDDE4DF)
private val LightAccent = Color(0xFF0E9E74)
private val LightDanger = Color(0xFFD2473F)

private val DarkBg = Color(0xFF111614)
private val DarkPanel = Color(0xFF161C19)
private val DarkPanel2 = Color(0xFF212A26)
private val DarkInk = Color(0xFFF1F5F2)
private val DarkInk2 = Color(0xFFB7C4BD)
private val DarkMuted = Color(0xFF7E8D86)
private val DarkLine = Color(0xFF2C3733)
private val DarkAccent = Color(0xFF45D6A6)
private val DarkDanger = Color(0xFFF08C82)

val Bg: Color get() = if (Look.dark) DarkBg else LightBg
val Panel: Color get() = if (Look.dark) DarkPanel else LightPanel
val Panel2: Color get() = if (Look.dark) DarkPanel2 else LightPanel2
val Ink: Color get() = if (Look.dark) DarkInk else LightInk
val Ink2: Color get() = if (Look.dark) DarkInk2 else LightInk2
val Muted: Color get() = if (Look.dark) DarkMuted else LightMuted
val Line: Color get() = if (Look.dark) DarkLine else LightLine
val Accent: Color get() = if (Look.dark) DarkAccent else LightAccent
val Danger: Color get() = if (Look.dark) DarkDanger else LightDanger

/** 压在强调色上的文字：浅色主题用白字，深色主题用深墨绿字。 */
val AccentInk: Color get() = if (Look.dark) Color(0xFF06211A) else Color(0xFFFFFFFF)

/** 强调色的浅底：选中态胶囊、聚焦环用。 */
val AccentSoft: Color get() = if (Look.dark) Color(0x2E45D6A6) else Color(0x1F0E9E74)

@Composable
private fun notesColorScheme(): ColorScheme =
    if (Look.dark) {
        darkColorScheme(
            primary = Accent,
            onPrimary = AccentInk,
            background = Bg,
            onBackground = Ink,
            surface = Panel,
            onSurface = Ink,
            surfaceVariant = Panel2,
            onSurfaceVariant = Ink2,
            outline = Line,
            error = Danger,
            // M3 的菜单/弹层容器色来自 surfaceContainer 系列，这里显式给值，
            // 免得深色模式下菜单还是浅色皮肤（白底白字像白屏）
            surfaceContainerLowest = Panel,
            surfaceContainerLow = Panel,
            surfaceContainer = Panel2,
            surfaceContainerHigh = Panel2,
            surfaceContainerHighest = Panel2
        )
    } else {
        lightColorScheme(
            primary = Accent,
            onPrimary = AccentInk,
            background = Bg,
            onBackground = Ink,
            surface = Panel,
            onSurface = Ink,
            surfaceVariant = Panel2,
            onSurfaceVariant = Ink2,
            outline = Line,
            error = Danger,
            surfaceContainerLowest = Panel,
            surfaceContainerLow = Panel,
            surfaceContainer = Panel2,
            surfaceContainerHigh = Panel2,
            surfaceContainerHighest = Panel2
        )
    }

/**
 * 菜单/弹层专用：Popup 是独立的一次性合成，MaterialTheme 变化有时传不进去，
 * 这里在弹层自己的作用域里重新给一遍当前配色，保证深色模式下不会出现白底白字。
 */
@Composable
fun NotesMenuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = notesColorScheme(), typography = Typography(), content = content)
}

/** 统一外观的下拉菜单：容器色跟着当前主题走，深色模式不会白底白字。 */
@Composable
fun NotesDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    NotesMenuTheme {
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            content = content
        )
    }
}

@Composable
fun NotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = notesColorScheme(), typography = Typography(), content = content)
}

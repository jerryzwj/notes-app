package eu.wenjun.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.activity.viewModels
import kotlinx.coroutines.delay

private enum class Screen { LOGIN, EDITOR, BACKUP, LIST }

class MainActivity : ComponentActivity() {
    // 提前创建 VM：它会把 notes-look 里的外观偏好读进 Look.dark，
    // 保证第一帧 MaterialTheme 就是深色/浅色正确的那套（否则首次打开深色模式
    // 时 M3 的菜单、弹窗还是浅色皮肤，白底白字像白屏）。
    private val model: NotesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = model
        setContent {
            NotesTheme {
                // 状态栏 / 导航栏跟着当前外观走，浅色亮底用深色图标，深色亮字
                val view = LocalView.current
                SideEffect {
                    val window = this@MainActivity.window
                    window.statusBarColor = Bg.toArgb()
                    window.navigationBarColor = Bg.toArgb()
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !Look.dark
                    controller.isAppearanceLightNavigationBars = !Look.dark
                }
                Box(Modifier.fillMaxSize().background(Bg)) {
                    val screen = when {
                        vm.token == null -> Screen.LOGIN
                        vm.openNote != null -> Screen.EDITOR
                        vm.settingsOpen -> Screen.BACKUP
                        else -> Screen.LIST
                    }
                    // 进出编辑页 / 备份页带一点横向滑入，其余切面淡入淡出，避免生硬跳屏
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            val enter = fadeIn(tween(220)) + slideInHorizontally(tween(280)) { it / 12 }
                            enter togetherWith fadeOut(tween(160))
                        },
                        label = "screen"
                    ) { state ->
                        when (state) {
                            Screen.LOGIN -> LoginScreen(vm)
                            Screen.EDITOR -> EditorScreen(vm)
                            Screen.BACKUP -> BackupScreen(vm)
                            Screen.LIST -> ListScreen(vm)
                        }
                    }
                    ToastHost(vm)
                }
            }
        }
    }
}

@Composable
private fun ToastHost(vm: NotesViewModel) {
    val message = vm.toast
    if (message != null) {
        LaunchedEffect(message) {
            delay(2600)
            vm.toast = null
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(
                text = message,
                color = AccentInk,
                fontSize = 12.5.sp,
                modifier = Modifier
                    .padding(bottom = 64.dp, start = 24.dp, end = 24.dp)
                    .background(Accent, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }
}

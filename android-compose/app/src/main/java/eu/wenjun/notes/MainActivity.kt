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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

private enum class Screen { LOGIN, EDITOR, BACKUP, LIST }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NotesTheme {
                val vm: NotesViewModel = viewModel()
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
                color = Color(0xFF1A1409),
                fontSize = 12.5.sp,
                modifier = Modifier
                    .padding(bottom = 64.dp, start = 24.dp, end = 24.dp)
                    .background(Amber, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }
}

package eu.wenjun.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BackupScreen(vm: NotesViewModel) {
    BackHandler { vm.closeBackup() }
    val saved = vm.webDav
    var url by remember(saved) { mutableStateOf(saved?.url ?: "") }
    var user by remember(saved) { mutableStateOf(saved?.user ?: "") }
    var pass by remember(saved) { mutableStateOf(saved?.pass ?: "") }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = Ink2,
                modifier = Modifier
                    .clickable { vm.closeBackup() }
                    .padding(12.dp)
            )
            Text("WebDAV 备份", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "备份成一篇篇 Markdown 文件，放在 WebDAV 的「${Backup.FOLDER}」文件夹里。" +
                    "文件名就是笔记标题，数据库哪天挂了，用任何记事本或 Obsidian 都能直接打开看。",
                fontSize = 12.5.sp,
                color = Muted,
                lineHeight = 19.sp
            )

            Text(
                "自动规则：每次保存后等 10 分钟，期间没有新的改动就自动备份一次；" +
                    "连续写的话会一直往后顺延，不会边写边传。手动「立即备份」随时可以用。",
                fontSize = 12.sp,
                color = Ink2,
                lineHeight = 18.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel2, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )

            Field(
                label = "WebDAV 地址",
                value = url,
                placeholder = "https://dav.example.com/notes",
                onValueChange = { url = it }
            )
            Field(
                label = "用户名",
                value = user,
                placeholder = "可留空（有的网盘不需要）",
                onValueChange = { user = it }
            )
            Field(
                label = "密码 / 应用专用密码",
                value = pass,
                placeholder = "建议用应用专用密码",
                onValueChange = { pass = it },
                password = true
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "保存设置",
                    color = Ink2,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .background(Panel2, RoundedCornerShape(50.dp))
                        .clickable { vm.saveWebDav(url, user, pass) }
                        .padding(vertical = 13.dp),
                )
                Text(
                    text = if (vm.backupBusy) "备份中…" else "立即备份",
                    color = if (vm.backupBusy) Muted else AccentInk,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (vm.backupBusy) Panel2 else Accent,
                            RoundedCornerShape(50.dp)
                        )
                        .clickable {
                            // 先把手填的配置记住再备份，省得先点保存
                            vm.saveWebDav(url, user, pass)
                            vm.runBackup()
                        }
                        .padding(vertical = 13.dp),
                    textAlign = TextAlign.Center
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vm.backupBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(13.dp).width(13.dp),
                        color = Accent,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = vm.backupStatus ?: "还没有备份过",
                    fontSize = 12.sp,
                    color = if (vm.backupStatus?.startsWith("上次备份") == true) Accent else Muted
                )
            }

            if (vm.backupPending) {
                Text(
                    "有改动还没备份，10 分钟内不再改动就会自动备份",
                    fontSize = 11.5.sp,
                    color = Muted
                )
            }

            Text(
                "回收站里的笔记不会被备份；正文没有写成笔记的空白稿也不需要备份。" +
                    "每次备份会覆盖同名文件，标题改过的旧文件会留下来，可以手动清理。",
                fontSize = 11.5.sp,
                color = Muted,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    password: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, color = Muted)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(Panel2, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Ink, fontSize = 14.sp),
                cursorBrush = SolidColor(Accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(placeholder, color = Muted, fontSize = 13.sp)
                    inner()
                }
            )
        }
    }
}

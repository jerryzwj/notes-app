package eu.wenjun.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

private data class Draft(val title: String, val tags: String, val body: String)

private fun TextFieldValue.wrap(before: String, after: String, placeholder: String): TextFieldValue {
    val start = selection.min
    val end = selection.max
    val chosen = text.substring(start, end).ifEmpty { placeholder }
    val newText = text.substring(0, start) + before + chosen + after + text.substring(end)
    return TextFieldValue(newText, TextRange(start + before.length + chosen.length))
}

private fun TextFieldValue.withLinePrefix(prefix: String): TextFieldValue {
    val pos = selection.min
    val lineStart = if (pos == 0) 0 else {
        val idx = text.lastIndexOf('\n', pos - 1)
        if (idx < 0) 0 else idx + 1
    }
    val newText = text.substring(0, lineStart) + prefix + text.substring(lineStart)
    return TextFieldValue(newText, TextRange(pos + prefix.length))
}

private fun TextFieldValue.insert(snippet: String): TextFieldValue {
    val start = selection.min
    val end = selection.max
    val newText = text.substring(0, start) + snippet + text.substring(end)
    return TextFieldValue(newText, TextRange(start + snippet.length))
}

@Composable
fun EditorScreen(vm: NotesViewModel) {
    val note = vm.openNote ?: return

    var title by remember(note.id) { mutableStateOf(note.title) }
    var tagsText by remember(note.id) { mutableStateOf(note.tags.joinToString(", ")) }
    var body by remember(note.id) {
        mutableStateOf(TextFieldValue(note.body, TextRange(note.body.length)))
    }
    var preview by remember(note.id) {
        // 已有内容的笔记默认进预览（跟网页一致），空白新笔记直接给编辑
        mutableStateOf(note.title.isNotBlank() || note.body.isNotBlank())
    }
    var menu by remember { mutableStateOf(false) }

    val clipboard = LocalClipboardManager.current
    val pinned = note.pinned
    // 自动保存协程里要拿最新的置顶状态，否则置顶后继续输入会把旧的 false 写回去
    val pinnedNow by rememberUpdatedState(pinned)

    fun close() = vm.saveAndClose(title, body.text, parseTags(tagsText), pinned)
    BackHandler { close() }

    // 编辑后 600ms 落库，连续输入只保存最后一次
    LaunchedEffect(note.id) {
        snapshotFlow { Draft(title, tagsText, body.text) }
            .drop(1)
            .collectLatest { draft ->
                delay(600)
                vm.save(draft.title, draft.body, parseTags(draft.tags), pinnedNow)
            }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { close() },
                modifier = Modifier.size(34.dp).background(Panel2, RoundedCornerShape(50.dp))
            ) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = Ink2)
            }
            Text(
                text = when {
                    vm.isDraft -> "新笔记"
                    vm.saving -> "保存中…"
                    else -> "已保存"
                },
                fontSize = 12.sp,
                color = Muted
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (preview) "编辑" else "预览",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Accent,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .background(AccentSoft, RoundedCornerShape(50.dp))
                    .clickable { preview = !preview }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
            Box {
                IconButton(
                    onClick = { menu = true },
                    modifier = Modifier.size(34.dp).background(Panel2, RoundedCornerShape(50.dp))
                ) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = Ink2)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (pinned) "取消置顶" else "置顶", color = Ink) }, onClick = {
                        menu = false
                        vm.setPinned(!pinned)
                    })
                    DropdownMenuItem(text = { Text("分享链接", color = Ink) }, onClick = {
                        menu = false
                        vm.share(note.id) { url ->
                            clipboard.setText(AnnotatedString(url))
                            vm.toast = "链接已复制：$url"
                        }
                    })
                    DropdownMenuItem(text = { Text("复制全文", color = Ink) }, onClick = {
                        menu = false
                        val all = buildString {
                            if (title.isNotBlank()) append(title).append("\n\n")
                            append(body.text)
                        }
                        clipboard.setText(AnnotatedString(all))
                        vm.toast = "已复制全文"
                    })
                    DropdownMenuItem(text = { Text("删除", color = Danger) }, onClick = {
                        menu = false
                        vm.deleteCurrent()
                    })
                }
            }
        }

        if (preview) {
            // 预览里的文字要能长按选中、拖选择柄后复制
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MarkdownView(body.text)
                    Spacer(Modifier.height(24.dp))
                }
            }
        } else {
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                textStyle = TextStyle(color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Bold),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                decorationBox = { inner ->
                    Box {
                        if (title.isEmpty()) Text("标题", color = Muted, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        inner()
                    }
                }
            )
            BasicTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                singleLine = true,
                textStyle = TextStyle(color = Accent, fontSize = 12.5.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                decorationBox = { inner ->
                    Box {
                        if (tagsText.isEmpty()) Text("标签，用逗号分隔", color = Muted, fontSize = 12.5.sp)
                        inner()
                    }
                }
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            BasicTextField(
                value = body,
                onValueChange = { body = it },
                textStyle = TextStyle(color = Ink, fontSize = 15.sp, lineHeight = 24.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    Box {
                        if (body.text.isEmpty()) Text("开始写点什么…支持 Markdown", color = Muted, fontSize = 15.sp)
                        inner()
                    }
                }
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            FormatBar(
                onBold = { body = body.wrap("**", "**", "粗体") },
                onItalic = { body = body.wrap("*", "*", "斜体") },
                onHeading = { body = body.withLinePrefix("## ") },
                onBullet = { body = body.withLinePrefix("- ") },
                onTodo = { body = body.withLinePrefix("- [ ] ") },
                onCode = { body = body.wrap("`", "`", "代码") },
                onQuote = { body = body.withLinePrefix("> ") },
                onLink = { body = body.wrap("[", "](https://)", "链接文字") },
                onRule = { body = body.insert("\n\n---\n\n") },
                onPaste = {
                    val clip = clipboard.getText()?.text
                    if (clip.isNullOrEmpty()) {
                        vm.toast = "剪贴板里没有文字"
                    } else {
                        body = body.insert(clip)
                    }
                }
            )
        }
    }
}

@Composable
private fun FormatBar(
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onHeading: () -> Unit,
    onBullet: () -> Unit,
    onTodo: () -> Unit,
    onCode: () -> Unit,
    onQuote: () -> Unit,
    onLink: () -> Unit,
    onRule: () -> Unit,
    onPaste: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolButton(Icons.Rounded.FormatBold, "加粗", onBold)
        ToolButton(Icons.Rounded.FormatItalic, "斜体", onItalic)
        ToolButton(Icons.Rounded.FormatSize, "标题", onHeading)
        ToolButton(Icons.Rounded.FormatListBulleted, "列表", onBullet)
        ToolButton(Icons.Rounded.CheckBox, "待办", onTodo)
        ToolButton(Icons.Rounded.Code, "代码", onCode)
        ToolButton(Icons.Rounded.FormatQuote, "引用", onQuote)
        ToolButton(Icons.Rounded.Link, "链接", onLink)
        ToolButton(Icons.Rounded.Remove, "分割线", onRule)
        ToolButton(Icons.Rounded.ContentPaste, "粘贴", onPaste)
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
        Icon(icon, contentDescription = label, tint = Ink2, modifier = Modifier.size(18.dp))
    }
}

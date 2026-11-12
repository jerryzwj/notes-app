package eu.wenjun.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import android.content.Intent
import android.net.Uri

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListScreen(vm: NotesViewModel) {
    val shown = remember(vm.notes, vm.tab) {
        vm.notes.filter { meta ->
            when (vm.tab) {
                Tab.TRASH -> meta.deleted
                Tab.PINNED -> !meta.deleted && meta.pinned
                Tab.ALL -> !meta.deleted
            }
        }
    }

    LaunchedEffect(vm.token) {
        if (vm.token != null) vm.refresh()
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        ListHeader(vm)
        SearchBox(vm)
        TabsRow(vm)
        if (vm.opening) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(Accent))
        } else {
            Line()
        }

        val listState = rememberLazyListState()
        PullToRefreshBox(
            listState = listState,
            refreshing = vm.loading && shown.isNotEmpty(),
            onRefresh = { if (!vm.loading) vm.refresh() },
            modifier = Modifier.weight(1f)
        ) {
            if (shown.isEmpty() && vm.loading) {
                LoadingPlaceholder()
            } else if (shown.isEmpty()) {
                Text(
                    text = when (vm.tab) {
                        Tab.TRASH -> "回收站是空的"
                        Tab.PINNED -> "还没有置顶笔记"
                        Tab.ALL -> if (vm.query.isBlank()) "还没有笔记，点右上角「+」开始" else "没找到相关笔记"
                    },
                    color = Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(shown, key = { it.id }) { meta ->
                        // 刷新后行位置变化（置顶、新笔记插到最前）时平滑移动，不直接跳
                        Column(Modifier.animateItemPlacement()) {
                            NoteRow(vm, meta)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${shown.size} 条笔记", color = Muted, fontSize = 12.sp)
        }
    }

    vm.updateDialog?.let { info -> UpdateDialog(vm, info) }
}

/** 有新版本时的对话框：更新说明 + 下载 APK 的入口。 */
@Composable
private fun UpdateDialog(vm: NotesViewModel, info: Update.Info) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { vm.dismissUpdate() },
        containerColor = Panel2,
        title = {
            Text(
                "发现新版本 v${info.version}",
                color = Ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "当前 ${BuildConfig.VERSION_NAME}" +
                        if (info.publishedAt.isNotBlank()) " · 发布于 ${info.publishedAt}" else "",
                    color = Muted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    info.notes.ifBlank { "这次没写更新说明。" },
                    color = Ink2,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.dismissUpdate()
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url)))
                }.onFailure { vm.toast = "没有能打开链接的浏览器" }
            }) {
                Text("下载更新", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissUpdate() }) {
                Text("稍后", color = Muted)
            }
        }
    )
}

@Composable
private fun ListHeader(vm: NotesViewModel) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_mark),
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text("简·笔记本", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .height(34.dp)
                .background(Accent, RoundedCornerShape(50.dp))
                .clickable { vm.createNote() }
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "新建", tint = AccentInk, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(3.dp))
            Text("新建", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AccentInk)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(Panel2, RoundedCornerShape(50.dp))
                .clickable { vm.toggleLook() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (vm.darkLook) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                contentDescription = "切换外观",
                tint = Ink2,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Box {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(Panel2, RoundedCornerShape(50.dp))
                    .clickable { menu = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "更多",
                    tint = Ink2,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("刷新", color = Ink) }, onClick = {
                    menu = false
                    vm.refresh()
                })
                DropdownMenuItem(text = { Text("WebDAV 备份", color = Ink) }, onClick = {
                    menu = false
                    vm.openBackup()
                })
                DropdownMenuItem(
                    text = {
                        Text(
                            if (vm.hostBusy) "正在测速…" else "线路：${Host.label(vm.host)}",
                            color = Ink
                        )
                    },
                    onClick = {
                        menu = false
                        vm.retestHost()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("检查更新", color = Ink)
                            Text(
                                when {
                                    vm.updateChecking -> "正在查询 GitHub…"
                                    vm.updateInfo?.available == true -> "有新版本 v${vm.updateInfo?.version}"
                                    else -> "v${BuildConfig.VERSION_NAME}"
                                },
                                color = if (vm.updateInfo?.available == true) Accent else Muted,
                                fontSize = 11.sp
                            )
                        }
                    },
                    onClick = {
                        menu = false
                        vm.checkUpdate()
                    }
                )
                DropdownMenuItem(text = { Text("退出登录", color = Danger) }, onClick = {
                    menu = false
                    vm.logout()
                })
            }
        }
    }
}

@Composable
private fun SearchBox(vm: NotesViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .height(38.dp)
            .background(Panel2, RoundedCornerShape(19.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(10.dp))
        Icon(Icons.Rounded.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value = vm.query,
            onValueChange = { vm.updateQuery(it) },
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 14.sp),
            cursorBrush = SolidColor(Accent),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (vm.query.isEmpty()) Text("搜索标题、正文、标签…", color = Muted, fontSize = 14.sp)
                inner()
            }
        )
        Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun TabsRow(vm: NotesViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp)
    ) {
        Tab.entries.forEach { item ->
            val selected = vm.tab == item
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .background(if (selected) AccentSoft else Color.Transparent, RoundedCornerShape(50.dp))
                    .clickable { vm.selectTab(item) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.label,
                    fontSize = 13.sp,
                    color = if (selected) Accent else Muted,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun NoteRow(vm: NotesViewModel, meta: NoteMeta) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .background(Panel, RoundedCornerShape(14.dp))
            .clickable { vm.open(meta.id) }
            .padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (meta.pinned) {
                    Text("置顶", fontSize = 10.sp, color = Accent, modifier = Modifier.padding(end = 6.dp))
                }
                Text(
                    text = meta.title.ifBlank { "无标题" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (meta.excerpt.isNotBlank()) {
                Text(
                    text = meta.excerpt,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Row(
                modifier = Modifier.padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(formatTime(meta.updatedAt), fontSize = 11.5.sp, color = Muted)
                meta.tags.take(3).forEach { tag ->
                    Text("#$tag", fontSize = 11.5.sp, color = Accent)
                }
            }
        }
        Box {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(50.dp))
                    .clickable { menu = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "更多",
                    tint = Muted,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (meta.deleted) {
                    DropdownMenuItem(text = { Text("恢复", color = Ink) }, onClick = {
                        menu = false
                        vm.restore(meta.id)
                    })
                    DropdownMenuItem(text = { Text("彻底删除", color = Danger) }, onClick = {
                        menu = false
                        vm.trash(meta.id, purge = true)
                    })
                } else {
                    DropdownMenuItem(text = { Text(if (meta.pinned) "取消置顶" else "置顶", color = Ink) }, onClick = {
                        menu = false
                        vm.pin(meta.id, !meta.pinned)
                    })
                    DropdownMenuItem(text = { Text("分享链接", color = Ink) }, onClick = {
                        menu = false
                        vm.share(meta.id) { url ->
                            clipboard.setText(AnnotatedString(url))
                            vm.toast = "链接已复制：$url"
                        }
                    })
                    DropdownMenuItem(text = { Text("删除", color = Danger) }, onClick = {
                        menu = false
                        vm.trash(meta.id)
                    })
                }
            }
        }
    }
}

@Composable
private fun Line() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** 首次进入、本地还没有列表缓存时的占位：一个转圈 + 骨架条，别让用户对着空白猜。 */
@Composable
private fun LoadingPlaceholder() {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = Accent,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
            Text("正在同步云端笔记…", color = Muted, fontSize = 12.sp)
        }
        repeat(5) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.fillMaxWidth(0.5f).height(15.dp).background(Panel2, RoundedCornerShape(4.dp)))
                Box(Modifier.fillMaxWidth(0.92f).height(12.dp).background(Panel2, RoundedCornerShape(4.dp)))
                Box(Modifier.fillMaxWidth(0.4f).height(11.dp).background(Panel2, RoundedCornerShape(4.dp)))
            }
            Line()
        }
    }
}

/**
 * 下拉刷新：列表已经在顶部时，把多余的向下拖动接管成"拉动距离"，
 * 松手超过阈值就触发刷新。material3 1.2.0 没有现成的 PullToRefresh，这里自己实现。
 * 刷新期间顶部固定显示「正在刷新…」，完成后短暂显示「已刷新」再收起。
 */
@Composable
private fun PullToRefreshBox(
    listState: LazyListState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 64.dp.toPx() }
    val barPx = with(density) { 34.dp.toPx() }
    var pull by remember { mutableStateOf(0f) }
    var wasRefreshing by remember { mutableStateOf(false) }
    var showDone by remember { mutableStateOf(false) }

    LaunchedEffect(refreshing) {
        if (refreshing) {
            wasRefreshing = true
            showDone = false
        } else if (wasRefreshing) {
            wasRefreshing = false
            showDone = true
            delay(700)
            showDone = false
        }
    }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                // 顶部继续往下拖：父节点先接管，避免被列表的过度滚动效果吃掉
                if (available.y > 0f && atTop) {
                    pull = (pull + available.y * 0.6f).coerceAtMost(thresholdPx * 2f)
                    return Offset(0f, available.y)
                }
                // 往回拖：先把已经拉出的距离收回去
                if (available.y < 0f && pull > 0f) {
                    val next = (pull + available.y).coerceAtLeast(0f)
                    val consumed = next - pull
                    pull = next
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // 松手时统一收口：过阈值就刷新，同时把下拉距离归零
                if (pull > 0f) {
                    val fire = pull >= thresholdPx
                    pull = 0f
                    if (fire) onRefresh()
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    // 刷新中 / 刚刷新完时把顶部提示条固定住，其余时候跟着手指
    val shift = when {
        refreshing || showDone -> barPx
        else -> pull
    }

    Box(modifier.nestedScroll(connection)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = shift },
            content = content
        )
        if (shift > 0f) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { shift.toDp() })
                    .background(Panel),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                when {
                    refreshing -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            color = Accent,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(7.dp))
                        Text("正在刷新…", fontSize = 12.sp, color = Muted)
                    }
                    showDone -> Text("已刷新", fontSize = 12.sp, color = Accent)
                    else -> Text(
                        text = if (pull >= thresholdPx) "松开刷新" else "下拉刷新",
                        fontSize = 12.sp,
                        color = if (pull >= thresholdPx) Accent else Muted
                    )
                }
            }
        }
    }
}


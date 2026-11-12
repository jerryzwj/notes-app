package eu.wenjun.notes

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class Tab(val label: String) { ALL("全部"), PINNED("置顶"), TRASH("回收站") }

class NotesViewModel(context: Application) : AndroidViewModel(context) {

    private val api = Api(context).also { it.token = Session.token(context) }
    private val app: Application get() = getApplication()
    private var prefetchJob: Job? = null
    /** 本地新建但还没落库的笔记 id，用来挡住分享/删除这类需要云端已有记录的入口。 */
    private var draftId: String? = null

    var token by mutableStateOf(Session.token(context)); private set
    var notes by mutableStateOf<List<NoteMeta>>(emptyList()); private set
    var tab by mutableStateOf(Tab.ALL); private set
    var query by mutableStateOf(""); private set
    var loading by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var opening by mutableStateOf(false); private set
    var isDraft by mutableStateOf(false); private set
    var openNote by mutableStateOf<Note?>(null); private set
    var toast by mutableStateOf<String?>(null)
    var settingsOpen by mutableStateOf(false); private set
    var backupBusy by mutableStateOf(false); private set
    var backupStatus by mutableStateOf<String?>(null); private set
    var backupPending by mutableStateOf(false); private set
    var webDav by mutableStateOf<WebDavConfig?>(null); private set
    /** 当前走的域名（两条线路自动优选）。 */
    var host by mutableStateOf(api.base); private set
    var hostBusy by mutableStateOf(false); private set
    /** 检查更新：自动查到的结果只用来在菜单里提示，手动点才弹对话框。 */
    var updateInfo by mutableStateOf<Update.Info?>(null); private set
    var updateDialog by mutableStateOf<Update.Info?>(null); private set
    var updateChecking by mutableStateOf(false); private set
    private var autoBackupJob: Job? = null

    init {
        // 一条线路连不上时自动换另一条，并提示一次
        api.onHostChanged = { switched ->
            viewModelScope.launch(Dispatchers.Main.immediate) {
                host = switched
                toast = "这条线路没连上，已切到 ${Host.label(switched)}"
            }
        }
        // 冷启动先把上次的列表摆出来，界面不用先空着等网络
        if (token != null) notes = ListCache.load(context)
        // 超过 6 小时没测过速（或第一次用），后台静默挑一次更快的线路
        if (token != null && Host.shouldProbe(context)) {
            viewModelScope.launch {
                delay(1500)
                val best = withContext(Dispatchers.IO) { Host.probe() }
                if (best.ok) {
                    api.switchTo(best.host)
                    host = best.host
                }
            }
        }
        // 上次攒下改动却没等到自动备份就被杀掉了，这次进来补上
        if (token != null && WebDavStore.load(context)?.ready == true && BackupPrefs.hasPending(context)) {
            viewModelScope.launch {
                delay(3000)
                runAutoBackup()
            }
        }
        // 每天最多查一次 GitHub Release，有新版只写进菜单提示，不打扰
        if (Update.shouldCheck(context)) {
            viewModelScope.launch {
                delay(2500)
                updateInfo = withContext(Dispatchers.IO) { Update.check(app) }
            }
        }
    }

    /** 手动检查更新：有新版本弹对话框，没有就提示一句。 */
    fun checkUpdate() {
        if (updateChecking) return
        viewModelScope.launch {
            updateChecking = true
            try {
                val info = withContext(Dispatchers.IO) { Update.check(app) }
                updateInfo = info
                when {
                    info.error != null -> toast = info.error
                    info.available -> updateDialog = info
                    else -> toast = "已是最新版本（${BuildConfig.VERSION_NAME}）"
                }
            } catch (e: Exception) {
                toast = e.message ?: "检查更新失败"
            } finally {
                updateChecking = false
            }
        }
    }

    fun dismissUpdate() {
        updateDialog = null
    }

    /** 手动测速：两条线路各打一个静态清单文件，取快的；界面里能从菜单点。 */
    fun retestHost() {
        if (hostBusy) return
        viewModelScope.launch {
            hostBusy = true
            try {
                val best = withContext(Dispatchers.IO) { Host.probe() }
                if (!best.ok) {
                    toast = "两条线路都没连上，检查一下网络"
                    return@launch
                }
                val before = api.base
                api.switchTo(best.host)
                host = best.host
                toast = if (before == best.host) {
                    "当前线路 ${Host.label(best.host)} 最快（${best.millis} ms）"
                } else {
                    "已切到 ${Host.label(best.host)}（${best.millis} ms）"
                }
            } catch (e: Exception) {
                toast = e.message ?: "测速失败"
            } finally {
                hostBusy = false
            }
        }
    }

    fun login(password: String) {
        if (password.isBlank()) {
            toast = "请输入密码"
            return
        }
        viewModelScope.launch {
            loading = true
            try {
                val issued = withContext(Dispatchers.IO) { api.login(password) }
                Session.save(app, issued)
                token = issued
                toast = "登录成功，这台手机以后免密"
                load()
            } catch (e: Exception) {
                toast = e.message ?: "登录失败"
            } finally {
                loading = false
            }
        }
    }

    fun logout() {
        Session.clear(app)
        NoteCache.clear(app)
        prefetchJob?.cancel()
        draftId = null
        isDraft = false
        api.token = null
        token = null
        openNote = null
        notes = emptyList()
    }

    fun selectTab(value: Tab) {
        tab = value
        refresh()
    }

    fun updateQuery(value: String) {
        query = value
        refresh()
    }

    fun refresh() {
        if (token == null) return
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        loading = true
        try {
            notes = withContext(Dispatchers.IO) { api.notes(query) }
            if (query.isBlank()) withContext(Dispatchers.IO) { ListCache.save(app, notes) }
            prefetch()
        } catch (e: ApiException) {
            if (e.code == 401) {
                Session.clear(app)
                api.token = null
                token = null
                toast = "登录已失效，请重新输入密码"
            } else {
                toast = e.message
            }
        } catch (e: Exception) {
            toast = "网络不通，稍后重试"
        } finally {
            loading = false
        }
    }

    /**
     * 新建：本地直接开一张空白稿，立刻进编辑页，不等云端。
     * 第一次自动保存走 PUT 时后端按 id 建笔记（后端 saveNote 对不存在的 id 就是新建）。
     * 什么都没写就返回，则不会在云端留下空笔记。
     */
    fun createNote() {
        val id = UUID.randomUUID().toString()
        draftId = id
        isDraft = true
        openNote = Note(
            id = id,
            title = "",
            body = "",
            tags = emptyList(),
            pinned = false,
            updatedAt = System.currentTimeMillis(),
            deleted = false
        )
        tab = Tab.ALL
    }

    /**
     * 点开笔记：本地缓存的时间戳和列表一致就直接用，省掉一次约 800ms 的云端往返；
     * 时间戳不一致（别处改过 / 刚装 App）才去拉正文，拉回来顺手写缓存。
     */
    fun open(id: String) {
        draftId = null
        isDraft = false
        val meta = notes.firstOrNull { it.id == id }
        val cached = NoteCache.get(app, id)
        if (cached != null && meta != null && cached.updatedAt == meta.updatedAt) {
            openNote = cached
            return
        }
        viewModelScope.launch {
            opening = true
            try {
                val fresh = withContext(Dispatchers.IO) { api.note(id) }
                NoteCache.put(app, fresh)
                openNote = fresh
            } catch (e: Exception) {
                toast = e.message ?: "打开失败"
            } finally {
                opening = false
            }
        }
    }

    /** 列表加载完就把最近若干篇正文预取到本地，之后点开是瞬时的。 */
    private fun prefetch() {
        prefetchJob?.cancel()
        val targets = notes.filter { !it.deleted }.take(12)
        if (targets.isEmpty()) return
        prefetchJob = viewModelScope.launch {
            for (chunk in targets.chunked(3)) {
                coroutineScope {
                    chunk.map { meta ->
                        async(Dispatchers.IO) {
                            val cached = NoteCache.get(app, meta.id)
                            if (cached != null && cached.updatedAt == meta.updatedAt) return@async
                            runCatching { api.note(meta.id) }.getOrNull()?.let { NoteCache.put(app, it) }
                        }
                    }.awaitAll()
                }
            }
        }
    }

    private suspend fun applySaved(saved: Note) {
        withContext(Dispatchers.IO) { NoteCache.put(app, saved) }
        if (saved.id == draftId) {
            draftId = null
            isDraft = false
        }
        val meta = NoteMeta(
            id = saved.id,
            title = saved.title,
            excerpt = excerpt(saved.body),
            tags = saved.tags,
            pinned = saved.pinned,
            updatedAt = saved.updatedAt,
            deleted = saved.deleted
        )
        // 新笔记第一次落库时列表里还没有它，补进去并放到最前
        notes = if (notes.none { it.id == saved.id }) listOf(meta) + notes else notes.map {
            if (it.id == saved.id) meta else it
        }
        // 存下来一次改动，重置自动备份倒计时（10 分钟无新改动才备份）
        BackupPrefs.markSaved(app)
        scheduleAutoBackup()
    }

    fun save(title: String, body: String, tags: List<String>, pinned: Boolean) {
        val current = openNote ?: return
        if (current.title == title && current.body == body && current.tags == tags && current.pinned == pinned) return
        viewModelScope.launch {
            saving = true
            try {
                val saved = withContext(Dispatchers.IO) {
                    api.saveNote(current.copy(title = title, body = body, tags = tags, pinned = pinned))
                }
                openNote = saved
                applySaved(saved)
            } catch (e: Exception) {
                toast = e.message ?: "保存失败"
            } finally {
                saving = false
            }
        }
    }

    /** 返回列表前把最后一次改动落库，避免防抖窗口里的内容丢掉。 */
    fun saveAndClose(title: String, body: String, tags: List<String>, pinned: Boolean) {
        val current = openNote ?: return
        val dirty = current.title != title || current.body != body || current.tags != tags || current.pinned != pinned
        openNote = null
        if (!dirty) return
        viewModelScope.launch {
            saving = true
            try {
                val saved = withContext(Dispatchers.IO) {
                    api.saveNote(current.copy(title = title, body = body, tags = tags, pinned = pinned))
                }
                applySaved(saved)
            } catch (e: Exception) {
                toast = e.message ?: "保存失败"
            } finally {
                saving = false
                refresh()
            }
        }
    }

    fun setPinned(value: Boolean) {
        val current = openNote ?: return
        viewModelScope.launch {
            saving = true
            try {
                val saved = withContext(Dispatchers.IO) { api.saveNote(current.copy(pinned = value)) }
                openNote = saved
                applySaved(saved)
                toast = if (value) "已置顶" else "取消置顶"
            } catch (e: Exception) {
                toast = e.message ?: "操作失败"
            } finally {
                saving = false
            }
        }
    }

    /** 列表里的置顶需要先取回正文，避免 PUT 时把正文清空。 */
    fun pin(id: String, value: Boolean) {
        viewModelScope.launch {
            try {
                val full = withContext(Dispatchers.IO) { api.note(id) }
                val saved = withContext(Dispatchers.IO) { api.saveNote(full.copy(pinned = value)) }
                applySaved(saved)
                refresh()
            } catch (e: Exception) {
                toast = e.message ?: "操作失败"
            }
        }
    }

    fun trash(id: String, purge: Boolean = false) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.trash(id, purge) }
                withContext(Dispatchers.IO) { NoteCache.remove(app, id) }
                toast = if (purge) "已彻底删除" else "已移入回收站"
                refresh()
            } catch (e: Exception) {
                toast = e.message ?: "删除失败"
            }
        }
    }

    fun restore(id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.restore(id) }
                toast = "已恢复"
                refresh()
            } catch (e: Exception) {
                toast = e.message ?: "恢复失败"
            }
        }
    }

    fun share(id: String, onLink: (String) -> Unit) {
        if (id == draftId) {
            toast = "先写点内容，等自动保存完成再分享"
            return
        }
        viewModelScope.launch {
            try {
                val url = withContext(Dispatchers.IO) { api.share(id) }
                if (url.isBlank()) toast = "分享链接生成失败" else onLink(url)
            } catch (e: Exception) {
                toast = e.message ?: "分享失败"
            }
        }
    }

    // ---------- WebDAV 备份 ----------

    fun openBackup() {
        backupStatus = null
        webDav = WebDavStore.load(app)
        settingsOpen = true
        loadBackupRecord()
    }

    fun closeBackup() {
        settingsOpen = false
    }

    fun saveWebDav(url: String, user: String, pass: String) {
        if (url.isBlank()) {
            toast = "先填 WebDAV 地址"
            return
        }
        val config = WebDavConfig(url.trim(), user.trim(), pass)
        WebDavStore.save(app, config)
        webDav = config
        toast = "WebDAV 设置已保存"
        loadBackupRecord()
    }

    /** 拉一下 WebDAV 上的备份记录，界面上显示"上次备份是什么时候"。 */
    private fun loadBackupRecord() {
        val config = webDav ?: return
        viewModelScope.launch {
            backupStatus = try {
                val record = Backup.lastRecord(config)
                if (record == null) "还没有备份过" else "上次备份：${record.time}（${record.count} 篇）"
            } catch (e: Exception) {
                e.message ?: "读不到备份记录"
            }
        }
    }

    fun runBackup() {
        val config = webDav ?: run {
            toast = "先填 WebDAV 地址再备份"
            return
        }
        if (backupBusy) return
        viewModelScope.launch {
            backupBusy = true
            try {
                if (notes.isEmpty()) load()
                val result = Backup.run(api, notes, config)
                BackupPrefs.markBackedUp(app)
                backupPending = false
                backupStatus = "上次备份：${result.time}（${result.count} 篇）"
                toast = if (result.failed == 0) {
                    "已备份 ${result.count} 篇到 WebDAV"
                } else {
                    "备份完成 ${result.count} 篇，${result.failed} 篇失败"
                }
            } catch (e: Exception) {
                toast = e.message ?: "备份失败"
            } finally {
                backupBusy = false
            }
        }
    }

    /**
     * 每次保存成功后重新计时：10 分钟内又改了，就把这次备份往后推；
     * 真正安静满 10 分钟才自动跑一次，避免边写边备份。
     */
    private fun scheduleAutoBackup() {
        if (WebDavStore.load(app)?.ready != true) return
        autoBackupJob?.cancel()
        autoBackupJob = viewModelScope.launch {
            backupPending = true
            delay(Backup.AUTO_DELAY_MS)
            backupPending = false
            runAutoBackup()
        }
    }

    private suspend fun runAutoBackup() {
        if (backupBusy) return
        val config = WebDavStore.load(app) ?: return
        if (!config.ready) return
        backupBusy = true
        try {
            if (notes.isEmpty()) load()
            val result = Backup.run(api, notes, config)
            BackupPrefs.markBackedUp(app)
            backupStatus = "上次备份：${result.time}（${result.count} 篇）"
            // 自动备份失败要说一声，成功就安静（状态行里能看到时间）
            if (result.failed > 0) toast = "自动备份完成 ${result.count} 篇，${result.failed} 篇失败"
        } catch (e: Exception) {
            toast = "自动备份失败：" + (e.message ?: "网络问题")
        } finally {
            backupBusy = false
        }
    }

    fun deleteCurrent() {
        val current = openNote ?: return
        openNote = null
        // 还没落库的空白稿，直接丢掉就行，不用请求云端
        if (current.id == draftId) {
            draftId = null
            isDraft = false
            return
        }
        trash(current.id)
    }

    companion object {
        fun excerpt(body: String, limit: Int = 100): String =
            body.replace(Regex("```[\\s\\S]*?```"), " ")
                .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
                .replace(Regex("!\\[[^\\]]*]\\S*\\([^)]*\\)"), " ")
                .replace(Regex("\\[([^\\]]+)]\\S*\\([^)]*\\)"), "$1")
                .replace(Regex("[*_~`>|]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(limit)
    }
}


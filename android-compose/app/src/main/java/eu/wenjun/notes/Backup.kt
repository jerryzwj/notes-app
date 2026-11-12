package eu.wenjun.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupRecord(val time: String, val count: String)

data class BackupResult(val time: String, val count: Int, val failed: Int, val skipped: Int)

/**
 * 把全部笔记备份成一篇篇能直接读的 Markdown 文件，放到 WebDAV 的「记事本备份/」目录里。
 * 目的不是给程序解析，而是数据库挂了以后，用任何文本编辑器 / Obsidian 都能把内容翻出来。
 */
object Backup {
    const val FOLDER = "记事本备份"
    /** 自动备份的等待时间：保存后 10 分钟没有新改动才触发。 */
    const val AUTO_DELAY_MS = 10L * 60 * 1000
    private const val RECORD = "$FOLDER/_备份记录.md"
    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    suspend fun run(
        api: Api,
        metas: List<NoteMeta>,
        config: WebDavConfig
    ): BackupResult = withContext(Dispatchers.IO) {
        val dav = WebDav(config)
        dav.mkcol(FOLDER)

        val used = mutableSetOf<String>()
        var count = 0
        var failed = 0
        val active = metas.filter { !it.deleted }

        active.forEach { meta ->
            val note = runCatching { api.note(meta.id) }.getOrNull()
            if (note == null) {
                failed++
                return@forEach
            }
            val name = uniqueName(note.title, used)
            runCatching { dav.put("$FOLDER/$name.md", renderNote(note)) }
                .onSuccess { count++ }
                .onFailure { failed++ }
        }

        val time = stampFormat.format(Date(System.currentTimeMillis()))
        val skipped = metas.size - active.size
        dav.put(RECORD, renderRecord(time, count, failed, skipped))
        BackupResult(time, count, failed, skipped)
    }

    /** 读回上次备份记录。没有备份过就返回 null。 */
    suspend fun lastRecord(config: WebDavConfig): BackupRecord? = withContext(Dispatchers.IO) {
        val text = runCatching { WebDav(config).get(RECORD) }.getOrNull() ?: return@withContext null
        val time = Regex("备份时间[：:]\\s*(.+)").find(text)?.groupValues?.getOrNull(1)?.trim()
        val count = Regex("笔记数量[：:]\\s*([0-9]+)").find(text)?.groupValues?.getOrNull(1)?.trim()
        if (time.isNullOrBlank()) null else BackupRecord(time, count ?: "?")
    }

    private fun renderNote(note: Note): String = buildString {
        append("# ").append(if (note.title.isBlank()) "未命名" else note.title).append("\n\n")
        if (note.tags.isNotEmpty()) {
            append("- 标签：").append(note.tags.joinToString("、")).append("\n")
        }
        append("- 更新时间：").append(absoluteTime(note.updatedAt)).append("\n")
        append("- 笔记 ID：").append(note.id).append("\n\n")
        append("---\n\n")
        append(note.body.ifBlank { "（这条笔记没有正文）" })
        append("\n")
    }

    private fun renderRecord(time: String, count: Int, failed: Int, skipped: Int): String = buildString {
        append("# 记事本备份记录\n\n")
        append("- 备份时间：").append(time).append("\n")
        append("- 笔记数量：").append(count).append("\n")
        if (failed > 0) append("- 读取失败：").append(failed).append(" 篇（下次备份会重试）\n")
        if (skipped > 0) append("- 跳过回收站：").append(skipped).append(" 篇\n")
        append("- 备份位置：").append(FOLDER).append("/\n\n")
        append("每篇笔记一个 .md 文件，文件名就是笔记标题；")
        append("用手机自带文件管理器、记事本、Obsidian 都能直接打开查看。\n")
        append("即使站点或数据库失效，把这里的文件拷回去就是完整内容。\n")
    }

    private fun absoluteTime(millis: Long): String {
        if (millis <= 0) return "未知"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
    }

    /** 文件名用标题，去掉文件名里不能用的符号；同名时补一个短 id 后缀。 */
    private fun uniqueName(title: String, used: MutableSet<String>): String {
        val cleaned = title
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "-")
            .trim()
            .trim('.')
            .take(60)
            .ifBlank { "未命名" }
        var name = cleaned
        var index = 2
        while (name in used) {
            name = "$cleaned（$index）"
            index++
        }
        used.add(name)
        return name
    }
}

/**
 * 自动备份用的两个时间戳：最后一次保存的时刻、最后一次备份完成的时刻。
 * 用来判断"有没有攒着没备份的改动"，以及 App 被杀掉后下次启动能补上。
 */
object BackupPrefs {
    // 和 WebDavStore 共用一个偏好文件，退出登录时一并清掉
    private const val FILE = "webdav-config"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun markSaved(context: Context) {
        prefs(context).edit().putLong("lastSavedAt", System.currentTimeMillis()).apply()
    }

    fun markBackedUp(context: Context) {
        prefs(context).edit().putLong("lastBackupAt", System.currentTimeMillis()).apply()
    }

    /** 有改动、且已经超过等待时间还没备份 —— 通常是上次没等够就被杀掉了。 */
    fun hasPending(context: Context): Boolean {
        val saved = prefs(context).getLong("lastSavedAt", 0L)
        val backedUp = prefs(context).getLong("lastBackupAt", 0L)
        if (saved <= 0L || saved <= backedUp) return false
        return System.currentTimeMillis() - saved >= Backup.AUTO_DELAY_MS
    }
}

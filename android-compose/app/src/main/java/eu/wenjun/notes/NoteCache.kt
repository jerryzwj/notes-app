package eu.wenjun.notes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 正文本地缓存：点开笔记先用缓存渲染，避免每次都等云端往返（实测单篇约 800ms）。
 * 只在 updatedAt 与列表元信息一致时才直接使用，保证不会把旧内容当新内容显示。
 */
object NoteCache {
    private const val FILE = "notes-cache"
    private const val MAX_ENTRIES = 40

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(context: Context, id: String): Note? {
        val raw = prefs(context).getString("n:$id", null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            Note(
                id = json.optString("id", id),
                title = json.optString("title", ""),
                body = json.optString("body", ""),
                tags = json.optJSONArray("tags")?.let { array ->
                    (0 until array.length()).map { array.optString(it, "") }.filter { it.isNotBlank() }
                } ?: emptyList(),
                pinned = json.optBoolean("pinned", false),
                updatedAt = json.optLong("updatedAt", 0L),
                deleted = json.optBoolean("deleted", false)
            )
        }.getOrNull()
    }

    fun put(context: Context, note: Note) {
        if (note.id.isBlank()) return
        val json = JSONObject()
            .put("id", note.id)
            .put("title", note.title)
            .put("body", note.body)
            .put("tags", JSONArray(note.tags))
            .put("pinned", note.pinned)
            .put("updatedAt", note.updatedAt)
            .put("deleted", note.deleted)
        prefs(context).edit().putString("n:${note.id}", json.toString()).apply()
        trim(context)
    }

    fun remove(context: Context, id: String) {
        prefs(context).edit().remove("n:$id").apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** 只留最近更新的若干条，防止缓存无限长大。 */
    private fun trim(context: Context) {
        val all = prefs(context).all
        if (all.size <= MAX_ENTRIES) return
        val ordered = all.entries
            .filter { it.key.startsWith("n:") }
            .sortedBy { (_, value) ->
                runCatching { JSONObject(value as String).optLong("updatedAt", 0L) }.getOrDefault(0L)
            }
        val removeCount = all.size - MAX_ENTRIES
        val editor = prefs(context).edit()
        ordered.take(removeCount).forEach { editor.remove(it.key) }
        editor.apply()
    }
}

/**
 * 列表缓存：把上次拿到的摘要列表存下来，冷启动时先摆出来，
 * 再在后台拉一次真实列表，避免开屏先看到一片空白。
 */
object ListCache {
    private const val FILE = "notes-cache"
    private const val KEY = "list"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): List<NoteMeta> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val json = array.optJSONObject(i) ?: return@mapNotNull null
                NoteMeta(
                    id = json.optString("id", ""),
                    title = json.optString("title", ""),
                    excerpt = json.optString("excerpt", ""),
                    tags = json.optJSONArray("tags")?.let { tags ->
                        (0 until tags.length()).map { tags.optString(it, "") }.filter { it.isNotBlank() }
                    } ?: emptyList(),
                    pinned = json.optBoolean("pinned", false),
                    updatedAt = json.optLong("updatedAt", 0L),
                    deleted = json.optBoolean("deleted", false)
                )
            }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, list: List<NoteMeta>) {
        val array = JSONArray()
        list.forEach { note ->
            array.put(
                JSONObject()
                    .put("id", note.id)
                    .put("title", note.title)
                    .put("excerpt", note.excerpt)
                    .put("tags", JSONArray(note.tags))
                    .put("pinned", note.pinned)
                    .put("updatedAt", note.updatedAt)
                    .put("deleted", note.deleted)
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }
}

package eu.wenjun.notes

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(message: String, val code: Int = 0) : Exception(message)

data class NoteMeta(
    val id: String,
    val title: String,
    val excerpt: String,
    val tags: List<String>,
    val pinned: Boolean,
    val updatedAt: Long,
    val deleted: Boolean
)

data class Note(
    val id: String,
    val title: String,
    val body: String,
    val tags: List<String>,
    val pinned: Boolean,
    val updatedAt: Long,
    val deleted: Boolean
)

private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key, "")

private fun JSONObject.textList(key: String): List<String> {
    val arr = optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
}

private fun JSONObject.toMeta(): NoteMeta = NoteMeta(
    id = text("id"),
    title = text("title"),
    excerpt = text("excerpt"),
    tags = textList("tags"),
    pinned = optBoolean("pinned", false),
    updatedAt = optLong("updatedAt", 0L),
    deleted = optBoolean("deleted", false)
)

private fun JSONObject.toNote(): Note = Note(
    id = text("id"),
    title = text("title"),
    body = text("body"),
    tags = textList("tags"),
    pinned = optBoolean("pinned", false),
    updatedAt = optLong("updatedAt", 0L),
    deleted = optBoolean("deleted", false)
)

/** 直接对接已上线的 Cloudflare Worker（Upstash Redis 存数据），不需要另起后端。 */
class Api(context: android.content.Context, initialBase: String = Host.current(context)) {

    private val appContext = context.applicationContext

    /** 当前线路；连不上会自己换到另一个域名并记住。 */
    var base: String = initialBase
        private set

    /** 线路变了（自动兜底换的）时通知界面。 */
    var onHostChanged: ((String) -> Unit)? = null

    /** 手动/测速后切换线路并记住。 */
    fun switchTo(host: String) {
        if (host !in Host.ALL) return
        base = host
        Host.save(appContext, host)
    }

    var token: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun execute(at: String, method: String, path: String, body: JSONObject?): JSONObject {
        val builder = Request.Builder()
            .url(at + path)
            .header("User-Agent", "NotesNative/2.0 (Android)")
        token?.let { builder.header("Authorization", "Bearer $it") }
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(JSON))
            "PUT" -> builder.put((body ?: JSONObject()).toString().toRequestBody(JSON))
            "DELETE" -> builder.delete()
        }
        return client.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = runCatching { JSONObject(raw).text("error") }.getOrDefault("")
                throw ApiException(msg.ifBlank { "请求失败（HTTP ${response.code}）" }, response.code)
            }
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        return try {
            execute(base, method, path, body)
        } catch (e: java.io.IOException) {
            // 主线路连不上（断网、DNS、超时）就换另一个域名再试一次，成功就记住它
            val alt = Host.other(base) ?: throw e
            val switched = try {
                execute(alt, method, path, body)
            } catch (second: java.io.IOException) {
                throw e
            }
            base = alt
            Host.save(appContext, alt)
            onHostChanged?.invoke(alt)
            switched
        }
    }

    fun login(password: String): String {
        val json = call("POST", "/api/login", JSONObject().put("password", password).put("device", true))
        val t = json.text("token")
        if (t.isBlank()) throw ApiException("登录失败")
        token = t
        return t
    }

    fun checkSession() = call("GET", "/api/session")

    fun notes(query: String): List<NoteMeta> {
        val q = if (query.isBlank()) "" else "&q=" + URLEncoder.encode(query, "UTF-8")
        val json = call("GET", "/api/notes?meta=1$q")
        val arr: JSONArray = json.optJSONArray("notes") ?: JSONArray()
        return (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            if (o.text("id").isBlank()) null else o.toMeta()
        }
    }

    fun note(id: String): Note {
        val json = call("GET", "/api/notes/$id")
        return (json.optJSONObject("note") ?: throw ApiException("笔记不存在")).toNote()
    }

    fun createNote(): Note {
        val json = call("POST", "/api/notes", JSONObject().put("title", "").put("body", ""))
        return (json.optJSONObject("note") ?: throw ApiException("新建失败")).toNote()
    }

    fun saveNote(note: Note): Note {
        val payload = JSONObject()
            .put("title", note.title)
            .put("body", note.body)
            .put("tags", JSONArray(note.tags))
            .put("pinned", note.pinned)
        val json = call("PUT", "/api/notes/${note.id}", payload)
        return (json.optJSONObject("note") ?: throw ApiException("保存失败")).toNote()
    }

    fun trash(id: String, purge: Boolean = false) {
        call("DELETE", "/api/notes/$id" + if (purge) "?purge=1" else "")
    }

    fun restore(id: String) {
        call("POST", "/api/notes/$id/restore", JSONObject())
    }

    fun share(id: String): String {
        val json = call("POST", "/api/notes/$id/share", JSONObject())
        val url = json.text("url")
        if (url.isNotBlank()) return url
        val slug = json.text("slug")
        return if (slug.isBlank()) "" else "$base/s/$slug"
    }

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

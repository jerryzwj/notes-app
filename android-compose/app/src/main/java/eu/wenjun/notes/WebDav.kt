package eu.wenjun.notes

import android.content.Context
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class WebDavConfig(
    val url: String,
    val user: String,
    val pass: String
) {
    val ready: Boolean get() = url.isNotBlank()
}

object WebDavStore {
    private const val FILE = "webdav-config"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): WebDavConfig? {
        val url = prefs(context).getString("url", null) ?: return null
        if (url.isBlank()) return null
        return WebDavConfig(
            url = url,
            user = prefs(context).getString("user", "") ?: "",
            pass = prefs(context).getString("pass", "") ?: ""
        )
    }

    fun save(context: Context, config: WebDavConfig) {
        prefs(context).edit()
            .putString("url", config.url.trim())
            .putString("user", config.user.trim())
            .putString("pass", config.pass)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}

class WebDavException(message: String) : Exception(message)

/**
 * 只用到 WebDAV 最基本的三件事：建目录、写文件、读文件。
 * 备份产物是给人看的 Markdown，所以这里不需要 PROPFIND / 列表接口。
 */
class WebDav(private val config: WebDavConfig) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val auth: String = "Basic " + Base64.encodeToString(
        "${config.user}:${config.pass}".toByteArray(Charsets.UTF_8),
        Base64.NO_WRAP
    )

    private fun url(path: String): String {
        val base = config.url.trim().trimEnd('/')
        if (path.isBlank()) return base
        val encoded = path.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        return "$base/$encoded"
    }

    fun mkcol(path: String) {
        val request = Request.Builder()
            .url(url(path))
            .method("MKCOL", null)
            .header("Authorization", auth)
            .build()
        client.newCall(request).execute().use { response ->
            // 405 = 目录已存在，算成功
            if (!response.isSuccessful && response.code != 405) {
                throw WebDavException(explain(response.code))
            }
        }
    }

    fun put(path: String, text: String) {
        val request = Request.Builder()
            .url(url(path))
            .put(text.toRequestBody("text/markdown; charset=utf-8".toMediaType()))
            .header("Authorization", auth)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw WebDavException(explain(response.code))
        }
    }

    fun get(path: String): String? {
        val request = Request.Builder()
            .url(url(path))
            .get()
            .header("Authorization", auth)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw WebDavException(explain(response.code))
            return response.body?.string()
        }
    }

    private fun explain(code: Int): String = when (code) {
        401 -> "WebDAV 用户名或密码不对"
        403 -> "WebDAV 拒绝写入，检查权限"
        404 -> "WebDAV 地址不对，找不到目录"
        409 -> "WebDAV 上级目录不存在"
        507 -> "WebDAV 空间不足"
        else -> "WebDAV 出错（HTTP $code）"
    }
}

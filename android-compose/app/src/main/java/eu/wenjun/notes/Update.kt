package eu.wenjun.notes

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 版本更新检查：读 GitHub Release 的 latest，跟本地 BuildConfig.VERSION_NAME 比。
 * 仓库名存在偏好文件里，默认 jerryzwj/notes-app；仓库还没建/没发版时只报一句人话，不崩。
 */
object Update {
    const val DEFAULT_REPO = "jerryzwj/notes-app"
    private const val FILE = "notes-update"
    private const val KEY_REPO = "repo"
    private const val KEY_CHECKED = "checkedAt"

    /** 自动检查间隔：一天一次，避免每次开 App 都打 GitHub。 */
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    data class Info(
        val available: Boolean,
        val version: String,
        val notes: String,
        val url: String,
        val publishedAt: String,
        val error: String? = null
    )

    fun repo(context: Context): String {
        val saved = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_REPO, null)
        return saved?.takeIf { it.contains('/') } ?: DEFAULT_REPO
    }

    fun setRepo(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_REPO, value.trim().trim('/'))
            .apply()
    }

    fun shouldCheck(context: Context): Boolean {
        val last = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(KEY_CHECKED, 0L)
        return System.currentTimeMillis() - last > CHECK_INTERVAL_MS
    }

    private fun markChecked(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong(KEY_CHECKED, System.currentTimeMillis())
            .apply()
    }

    /** 阻塞调用，请在 IO 线程里跑。 */
    fun check(context: Context): Info {
        val repo = repo(context)
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "NotesNative/${BuildConfig.VERSION_NAME} (Android)")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                when {
                    response.code == 404 -> Info(
                        false, "", "",
                        "https://github.com/$repo/releases", "",
                        "还没有发布记录（仓库 $repo 不可访问或没发过版）"
                    )
                    !response.isSuccessful -> Info(
                        false, "", "",
                        "https://github.com/$repo/releases", "",
                        "GitHub 返回 ${response.code}，稍后再试"
                    )
                    else -> {
                        val json = JSONObject(raw)
                        val tag = json.optString("tag_name", "").trim()
                        val version = tag.removePrefix("v").removePrefix("V")
                        val assets = json.optJSONArray("assets")
                        var apkUrl = ""
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val a = assets.optJSONObject(i) ?: continue
                                if (a.optString("name", "").endsWith(".apk", true)) {
                                    apkUrl = a.optString("browser_download_url", "")
                                    if (apkUrl.isNotBlank()) break
                                }
                            }
                        }
                        val page = json.optString("html_url", "https://github.com/$repo/releases")
                        markChecked(context)
                        Info(
                            available = isNewer(version, BuildConfig.VERSION_NAME),
                            version = version,
                            notes = json.optString("body", "").trim(),
                            url = apkUrl.ifBlank { page },
                            publishedAt = json.optString("published_at", "").take(10)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Info(false, "", "", "https://github.com/$repo/releases", "", "连不上 GitHub：" + (e.message ?: "网络问题"))
        }
    }

    /** 只比较前面的数字段：2.1 > 2.0，2.1.3 > 2.1；"2.1-native" 也认。 */
    fun isNewer(remote: String, local: String): Boolean {
        val a = numbers(remote)
        val b = numbers(local)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(version: String): List<Int> {
        val out = mutableListOf<Int>()
        val cleaned = version.trim().removePrefix("v").removePrefix("V")
        for (part in cleaned.split('.', '-', '_', ' ')) {
            val digits = part.takeWhile { it.isDigit() }
            if (digits.isEmpty()) break
            out.add(digits.toIntOrNull() ?: break)
        }
        return out
    }
}

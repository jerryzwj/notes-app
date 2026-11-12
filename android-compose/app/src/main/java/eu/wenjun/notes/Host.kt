package eu.wenjun.notes

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 两个域名指向同一个 Worker，内容完全一样。
 * 这里做「优选」：开 App 时测一下哪条线路快，慢的那条只作为断线时的兜底。
 * 测速用静态清单文件，不碰 Redis，量到的就是 DNS/建连/TLS 的真实耗时。
 */
object Host {
    const val PRIMARY = "https://note.wenjun.eu.org"
    const val BACKUP = "https://note.zhangwenjun.eu.org"
    val ALL = listOf(PRIMARY, BACKUP)

    private const val FILE = "notes-host"
    private const val KEY_HOST = "host"
    private const val KEY_PROBED = "probedAt"
    private const val PROBE_PATH = "/manifest.webmanifest"

    /** 多久重新测一次；期间直接用上次的结果，避免每次开 App 都多打两个请求。 */
    private const val RECHECK_MS = 6L * 60 * 60 * 1000

    fun current(context: Context): String {
        val saved = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_HOST, null)
        return if (saved != null && saved in ALL) saved else PRIMARY
    }

    fun save(context: Context, host: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, host)
            .putLong(KEY_PROBED, System.currentTimeMillis())
            .apply()
    }

    fun other(host: String): String? = ALL.firstOrNull { it != host }

    fun shouldProbe(context: Context): Boolean {
        val last = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(KEY_PROBED, 0L)
        return System.currentTimeMillis() - last > RECHECK_MS
    }

    /** 名字给界面用：note.wenjun.eu.org 这种。 */
    fun label(url: String): String = url.removePrefix("https://")

    fun probe(): HostChoice {
        val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
        val results = ALL.map { host ->
            val started = System.nanoTime()
            val ok = runCatching {
                val request = Request.Builder()
                    .url(host + PROBE_PATH)
                    .header("User-Agent", "NotesNative/2.0 (Android)")
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            HostChoice(host, ok, (System.nanoTime() - started) / 1_000_000)
        }
        val fastest = results.filter { it.ok }.minByOrNull { it.millis }
        return fastest ?: results.first()
    }
}

data class HostChoice(val host: String, val ok: Boolean, val millis: Long)

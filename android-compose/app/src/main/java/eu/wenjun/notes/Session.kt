package eu.wenjun.notes

import android.content.Context

/** 令牌放在应用私有 SharedPreferences 里，卸载即清；退出登录会主动清空。 */
object Session {
    private const val FILE = "notes-session"
    private const val KEY = "token"

    fun token(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun save(context: Context, token: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, token).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

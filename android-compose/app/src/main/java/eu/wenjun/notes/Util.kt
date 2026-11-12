package eu.wenjun.notes

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun parseTags(raw: String): List<String> =
    raw.split(',', '，', '#', ' ', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(10)

fun formatTime(millis: Long): String {
    if (millis <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> SimpleDateFormat("今天 HH:mm", Locale.CHINA).format(Date(millis))
        sameYear && dayDiff == 1 -> "昨天"
        sameYear -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(millis))
        else -> SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(millis))
    }
}

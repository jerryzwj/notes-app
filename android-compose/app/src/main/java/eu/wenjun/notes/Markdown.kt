package eu.wenjun.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Item(val text: String, val ordered: Boolean, val index: Int, val check: Boolean?) : MdBlock
    data object Rule : MdBlock
}

private val HEADING = Regex("^#{1,6}\\s+")
private val ORDERED = Regex("^\\s*\\d+[.)]\\s+")
private val BULLET = Regex("^\\s*[-*+]\\s+")
private val CHECKBOX = Regex("^\\s*[-*+]\\s+\\[[ xX]]\\s*")
private val RULE = Regex("^\\s*([-*_])\\1{2,}\\s*$")

/** 轻量 Markdown：标题、列表、待办、引用、代码块、分割线、加粗/斜体/行内代码/链接。 */
fun parseMarkdown(source: String): List<MdBlock> = parseBlocks(source).map { it.first }

/**
 * 解析 Markdown，同时带回「这块文本在源码里的起始下标」。
 * 预览里点正文要切回编辑并把光标落到点击处，就得靠这个下标。
 */
fun parseBlocks(source: String): List<Pair<MdBlock, Int>> {
    val places = mutableListOf<Pair<MdBlock, Int>>()
    val text = source.replace("\r\n", "\n")
    val lines = text.split("\n")
    val starts = IntArray(lines.size)
    var acc = 0
    for (k in lines.indices) {
        starts[k] = acc
        acc += lines[k].length + 1
    }
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        when {
            line.trimStart().startsWith("```") -> {
                val buffer = StringBuilder()
                i++
                val first = i
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    buffer.append(lines[i]).append('\n')
                    i++
                }
                val body = buffer.toString().trimEnd()
                val start = if (first < lines.size) starts[first] else starts[first - 1]
                if (i < lines.size) i++
                places += MdBlock.Code(body) to start
            }

            line.isBlank() -> i++

            RULE.matches(line) -> {
                places += MdBlock.Rule to starts[i]
                i++
            }

            HEADING.containsMatchIn(line) -> {
                val m = HEADING.find(line)!!
                places += MdBlock.Heading(
                    m.value.trim().length.coerceAtMost(6),
                    line.replaceFirst(HEADING, "")
                ) to (starts[i] + m.range.last + 1)
                i++
            }

            line.trimStart().startsWith(">") -> {
                val buffer = StringBuilder()
                val first = i
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    buffer.append(lines[i].trimStart().removePrefix(">").trim()).append('\n')
                    i++
                }
                val body = buffer.toString().trim()
                val lead = lines[first].length - lines[first].trimStart().length
                places += MdBlock.Quote(body) to (starts[first] + lead + 1)
            }

            CHECKBOX.containsMatchIn(line) -> {
                val m = CHECKBOX.find(line)!!
                val checked = m.value.contains("[x]") || m.value.contains("[X]")
                places += MdBlock.Item(line.replaceFirst(CHECKBOX, ""), false, 0, checked) to
                    (starts[i] + m.range.last + 1)
                i++
            }

            ORDERED.containsMatchIn(line) -> {
                val m = ORDERED.find(line)!!
                val prefix = m.value
                val index = prefix.trim().substringBefore('.').substringBefore(')').toIntOrNull() ?: 1
                places += MdBlock.Item(line.replaceFirst(ORDERED, ""), true, index, null) to
                    (starts[i] + m.range.last + 1)
                i++
            }

            BULLET.containsMatchIn(line) -> {
                val m = BULLET.find(line)!!
                places += MdBlock.Item(line.replaceFirst(BULLET, ""), false, 0, null) to
                    (starts[i] + m.range.last + 1)
                i++
            }

            else -> {
                val buffer = StringBuilder()
                val first = i
                while (i < lines.size) {
                    val current = lines[i]
                    if (current.isBlank() || HEADING.containsMatchIn(current) ||
                        RULE.matches(current) || current.trimStart().startsWith("```") ||
                        current.trimStart().startsWith(">") || BULLET.containsMatchIn(current)
                    ) break
                    buffer.append(current).append('\n')
                    i++
                }
                val body = buffer.toString().trim()
                val lead = lines[first].length - lines[first].trimStart().length
                places += MdBlock.Para(body) to (starts[first] + lead)
            }
        }
    }
    return places
}

private val INLINE = Regex("""\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`|\[(.+?)]\((.+?)\)|~~(.+?)~~""")

fun inlineText(text: String, base: Color = Ink, accent: Color = Accent): AnnotatedString =
    buildAnnotatedString {
        var last = 0
        for (match in INLINE.findAll(text)) {
            if (match.range.first > last) append(text.substring(last, match.range.first))
            val g = match.groupValues
            when {
                g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = base)) { append(g[1]) }
                g[2].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = base)) { append(g[2]) }
                g[3].isNotEmpty() -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, color = Ink2, background = Panel2)
                ) { append(" " + g[3] + " ") }

                g[4].isNotEmpty() -> withStyle(
                    SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
                ) { append(g[4]) }

                g[6].isNotEmpty() -> withStyle(
                    SpanStyle(color = Muted, textDecoration = TextDecoration.LineThrough)
                ) { append(g[6]) }

                else -> append(match.value)
            }
            last = match.range.last + 1
        }
        if (last < text.length) append(text.substring(last))
    }

/** 预览里点到的第 rendered 个字符，换算回 Markdown 原文里的下标（跳过 ** ` [ ]( ) 这些标记）。 */
private fun rawIndexForRendered(text: String, rendered: Int): Int {
    if (rendered <= 0) return 0
    var rawLast = 0
    var shownLast = 0
    for (match in INLINE.findAll(text)) {
        val plainEnd = match.range.first
        if (rendered <= shownLast + (plainEnd - rawLast)) return rawLast + (rendered - shownLast)
        shownLast += plainEnd - rawLast
        val g = match.groupValues
        val content: String
        val lead: Int
        when {
            g[1].isNotEmpty() -> { content = g[1]; lead = 2 }
            g[2].isNotEmpty() -> { content = g[2]; lead = 1 }
            g[3].isNotEmpty() -> { content = g[3]; lead = 1 }
            g[4].isNotEmpty() -> { content = g[4]; lead = 1 }
            g[6].isNotEmpty() -> { content = g[6]; lead = 2 }
            else -> { content = match.value; lead = 0 }
        }
        val shownLen = if (g[3].isNotEmpty()) content.length + 2 else content.length
        if (rendered <= shownLast + shownLen) {
            val inside = (rendered - shownLast - if (g[3].isNotEmpty()) 1 else 0).coerceAtLeast(0)
            return match.range.first + lead + inside
        }
        shownLast += shownLen
        rawLast = match.range.last + 1
    }
    return (rawLast + (rendered - shownLast)).coerceAtMost(text.length)
}

@Composable
fun MarkdownView(
    source: String,
    modifier: Modifier = Modifier,
    onTapPosition: ((Int) -> Unit)? = null
) {
    val blocks = remember(source) { parseBlocks(source) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { (block, start) -> BlockView(block, start, onTapPosition) }
    }
}

/** 一段可点击定位的文本：把点击落点换算成源码下标后回调出去。 */
@Composable
private fun TappableText(
    raw: String,
    start: Int,
    onTap: ((Int) -> Unit)?,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    textDecoration: TextDecoration? = null,
    modifier: Modifier = Modifier,
    styled: Boolean = true
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // 只认「短按」；按下/抬起都不消费事件，预览里的长按选区、拖动选择柄复制不受影响
    val tapMod = if (onTap == null) Modifier else Modifier.pointerInput(raw, start) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val up = waitForUpOrCancellation()
            if (up != null && (up.uptimeMillis - down.uptimeMillis) <= 450L) {
                val result = layout ?: return@awaitEachGesture
                val rendered = result.getOffsetForPosition(up.position)
                val rawIndex = if (styled) rawIndexForRendered(raw, rendered) else rendered.coerceAtMost(raw.length)
                onTap(start + rawIndex)
            }
        }
    }
    Text(
        text = if (styled) inlineText(raw, color) else AnnotatedString(raw),
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        textDecoration = textDecoration,
        color = color,
        onTextLayout = { layout = it },
        modifier = modifier.then(tapMod)
    )
}

@Composable
private fun BlockView(block: MdBlock, start: Int, onTap: ((Int) -> Unit)?) {
    when (block) {
        is MdBlock.Heading -> {
            val size = when (block.level) {
                1 -> 24.sp
                2 -> 19.sp
                else -> 17.sp
            }
            TappableText(
                raw = block.text,
                start = start,
                onTap = onTap,
                color = Ink,
                fontSize = size,
                lineHeight = size * 1.35f,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        is MdBlock.Para -> TappableText(
            raw = block.text,
            start = start,
            onTap = onTap,
            color = Ink,
            fontSize = 15.sp,
            lineHeight = 25.sp
        )

        is MdBlock.Code -> TappableText(
            raw = block.text,
            start = start,
            onTap = onTap,
            color = Ink2,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            fontFamily = FontFamily.Monospace,
            styled = false,
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel2, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        )

        is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
            Spacer(
                Modifier
                    .width(2.dp)
                    .height(0.dp)
            )
            TappableText(
                raw = block.text,
                start = start,
                onTap = onTap,
                color = Ink2,
                fontSize = 14.sp,
                lineHeight = 23.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel2, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }

        is MdBlock.Item -> Row(Modifier.fillMaxWidth()) {
            Text(
                text = when {
                    block.check != null -> if (block.check) "☑" else "☐"
                    block.ordered -> "${block.index}."
                    else -> "•"
                },
                fontSize = 15.sp,
                lineHeight = 24.sp,
                color = if (block.check == true) Muted else Accent,
                modifier = Modifier.width(22.dp)
            )
            TappableText(
                raw = block.text,
                start = start,
                onTap = onTap,
                color = if (block.check == true) Muted else Ink,
                fontSize = 15.sp,
                lineHeight = 24.sp,
                textDecoration = if (block.check == true) TextDecoration.LineThrough else null
            )
        }

        MdBlock.Rule -> Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Line)
        )
    }
}

package eu.wenjun.notes

import androidx.compose.foundation.background
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle

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
fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = source.replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        when {
            line.trimStart().startsWith("```") -> {
                val buffer = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    buffer.append(lines[i]).append('\n')
                    i++
                }
                if (i < lines.size) i++
                blocks += MdBlock.Code(buffer.toString().trimEnd())
            }

            line.isBlank() -> i++

            RULE.matches(line) -> {
                blocks += MdBlock.Rule
                i++
            }

            HEADING.containsMatchIn(line) -> {
                val level = HEADING.find(line)!!.value.trim().length
                blocks += MdBlock.Heading(level.coerceAtMost(6), line.replaceFirst(HEADING, ""))
                i++
            }

            line.trimStart().startsWith(">") -> {
                val buffer = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    buffer.append(lines[i].trimStart().removePrefix(">").trim()).append('\n')
                    i++
                }
                blocks += MdBlock.Quote(buffer.toString().trim())
            }

            CHECKBOX.containsMatchIn(line) -> {
                val checked = CHECKBOX.find(line)!!.value.contains("[x]") || CHECKBOX.find(line)!!.value.contains("[X]")
                blocks += MdBlock.Item(line.replaceFirst(CHECKBOX, ""), false, 0, checked)
                i++
            }

            ORDERED.containsMatchIn(line) -> {
                val prefix = ORDERED.find(line)!!.value
                val index = prefix.trim().substringBefore('.').substringBefore(')').toIntOrNull() ?: 1
                blocks += MdBlock.Item(line.replaceFirst(ORDERED, ""), true, index, null)
                i++
            }

            BULLET.containsMatchIn(line) -> {
                blocks += MdBlock.Item(line.replaceFirst(BULLET, ""), false, 0, null)
                i++
            }

            else -> {
                val buffer = StringBuilder()
                while (i < lines.size) {
                    val current = lines[i]
                    if (current.isBlank() || HEADING.containsMatchIn(current) ||
                        RULE.matches(current) || current.trimStart().startsWith("```") ||
                        current.trimStart().startsWith(">") || BULLET.containsMatchIn(current)
                    ) break
                    buffer.append(current).append('\n')
                    i++
                }
                blocks += MdBlock.Para(buffer.toString().trim())
            }
        }
    }
    return blocks
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

@Composable
fun MarkdownView(source: String, modifier: Modifier = Modifier) {
    val blocks = parseMarkdown(source)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block -> BlockView(block) }
    }
}

@Composable
private fun BlockView(block: MdBlock) {
    when (block) {
        is MdBlock.Heading -> {
            val size = when (block.level) {
                1 -> 24.sp
                2 -> 19.sp
                else -> 17.sp
            }
            Text(
                text = inlineText(block.text),
                fontSize = size,
                lineHeight = size * 1.35f,
                fontWeight = FontWeight.Bold,
                color = Ink,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        is MdBlock.Para -> Text(
            text = inlineText(block.text),
            fontSize = 15.sp,
            lineHeight = 25.sp,
            color = Ink
        )

        is MdBlock.Code -> Text(
            text = block.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = Ink2,
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
            Text(
                text = inlineText(block.text),
                fontSize = 14.sp,
                lineHeight = 23.sp,
                color = Ink2,
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
            Text(
                text = inlineText(block.text),
                fontSize = 15.sp,
                lineHeight = 24.sp,
                color = if (block.check == true) Muted else Ink,
                textDecoration = if (block.check == true) TextDecoration.LineThrough else null
            )
        }

        MdBlock.Rule -> Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Line)
        )

        else -> Unit
    }
}

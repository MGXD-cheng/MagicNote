package com.magicnote.mgxd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * 轻量 Markdown 渲染（零依赖）
 *
 * 支持：
 * - 标题：`#` ~ `####`（1~4 级）
 * - 无序列表：`-` / `*` / `+`（支持一级缩进）
 * - 有序列表：`1.` `2.` …
 * - 引用：`>`（左侧竖线 + 淡背景）
 * - 分隔线：`---` / `***` / `___`
 * - 代码块：```lang … ```（等宽字体 + 底色）
 * - 行内：`**加粗**`、`*斜体*`、`` `code` ``、`~~删除线~~`
 *
 * 设计目标：聊天场景够用、渲染稳定（不追求完整 CommonMark）。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onPrimary: Boolean = false
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val codeBg = if (onPrimary) Color(0x33000000) else MaterialTheme.colorScheme.surface
    val monoFamily = FontFamily.Monospace

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inline(block.text, baseColor),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyMedium
                    },
                    fontWeight = FontWeight.Bold,
                    color = baseColor
                )

                is MdBlock.Bullet -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text("• ", style = MaterialTheme.typography.bodyMedium, color = baseColor)
                    Text(
                        text = inline(block.text, baseColor),
                        style = MaterialTheme.typography.bodyMedium,
                        color = baseColor
                    )
                }

                is MdBlock.Ordered -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "${block.number}. ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = baseColor
                    )
                    Text(
                        text = inline(block.text, baseColor),
                        style = MaterialTheme.typography.bodyMedium,
                        color = baseColor
                    )
                }

                is MdBlock.Quote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (onPrimary) Color(0x22000000) else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text("▍", color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = inline(block.text, baseColor),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = baseColor
                    )
                }

                is MdBlock.Code -> Text(
                    text = block.code,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = monoFamily,
                    color = baseColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(codeBg, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                )

                MdBlock.Divider -> HorizontalDivider(color = baseColor.copy(alpha = 0.3f))

                is MdBlock.Paragraph -> Text(
                    text = inline(block.text, baseColor),
                    style = MaterialTheme.typography.bodyMedium,
                    color = baseColor
                )
            }
        }
    }
}

/** Markdown 块级元素 */
private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Ordered(val number: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val code: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Divider : MdBlock
}

private val BULLET_RE = Regex("^\\s*[-*+]\\s+(.*)$")
private val ORDERED_RE = Regex("^\\s*(\\d{1,3})\\.\\s+(.*)$")
private val HEADING_RE = Regex("^(#{1,6})\\s+(.*)$")
private val DIVIDER_RE = Regex("^\\s*([-*_])\\1{2,}\\s*$")

/** 解析 Markdown 文本为块列表 */
private fun parseMarkdown(text: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = text.replace("\r\n", "\n").split("\n")
    var i = 0
    val para = StringBuilder()

    fun flushPara() {
        if (para.isNotBlank()) {
            out.add(MdBlock.Paragraph(para.toString().trim()))
            para.clear()
        }
    }

    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        when {
            // 代码块
            line.trimStart().startsWith("```") -> {
                flushPara()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                out.add(MdBlock.Code(code.toString().trimEnd()))
            }
            // 标题
            HEADING_RE.matches(line) -> {
                flushPara()
                val m = HEADING_RE.find(line)!!
                out.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
            }
            // 分隔线
            DIVIDER_RE.matches(line) -> {
                flushPara()
                out.add(MdBlock.Divider)
            }
            // 引用
            line.trimStart().startsWith(">") -> {
                flushPara()
                out.add(MdBlock.Quote(line.trimStart().removePrefix(">").trim()))
            }
            // 无序列表
            BULLET_RE.matches(line) -> {
                flushPara()
                out.add(MdBlock.Bullet(BULLET_RE.find(line)!!.groupValues[1]))
            }
            // 有序列表
            ORDERED_RE.matches(line) -> {
                flushPara()
                val m = ORDERED_RE.find(line)!!
                out.add(MdBlock.Ordered(m.groupValues[1].toIntOrNull() ?: 1, m.groupValues[2]))
            }
            // 空行
            line.isBlank() -> flushPara()
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line.trim())
            }
        }
        i++
    }
    flushPara()
    return out
}

/** 行内样式：**粗体** *斜体* `code` ~~删除线~~ */
private fun inline(text: String, baseColor: Color): AnnotatedString = buildAnnotatedString {
    var idx = 0
    val s = text
    while (idx < s.length) {
        val rest = s.substring(idx)
        val match = INLINE_RE.find(rest)
        if (match == null) {
            append(rest)
            break
        }
        if (match.range.first > 0) append(rest.substring(0, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(token.removeSurrounding("**"))
            }
            token.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(token.removeSurrounding("__"))
            }
            token.startsWith("~~") -> withStyle(
                SpanStyle(textDecoration = TextDecoration.LineThrough)
            ) { append(token.removeSurrounding("~~")) }
            token.startsWith("`") -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = baseColor.copy(alpha = 0.12f)
                )
            ) { append(token.removeSurrounding("`")) }
            token.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(token.removeSurrounding("*"))
            }
            token.startsWith("_") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(token.removeSurrounding("_"))
            }
            else -> append(token)
        }
        idx += match.range.last + 1
    }
}

private val INLINE_RE = Regex(
    "\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__|~~[^~\\n]+~~|`[^`\\n]+`|\\*[^*\\n]+\\*|_[^_\\n]+_"
)

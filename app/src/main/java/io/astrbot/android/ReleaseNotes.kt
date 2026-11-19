package io.astrbot.android

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.commonmark.node.*
import org.commonmark.node.Text as MarkdownTextNode
import org.commonmark.node.Paragraph as MarkdownParagraph
import org.commonmark.parser.Parser
import java.net.URI

/** Native, selectable release notes. HTML and images never execute or load remote resources. */
@Composable internal fun ReleaseNotes(markdown: String, baseUrl: String = "https://github.com/JunWan666/astrbot-for-android/") {
    val document = remember(markdown) { Parser.builder().build().parse(markdown.take(32_000)) }
    SelectionContainer { MarkdownBlocks(document, baseUrl) }
}

private fun Node.children(): List<Node> = buildList {
    var child = firstChild
    while (child != null) { add(child); child = child.next }
}

@Composable private fun MarkdownBlocks(parent: Node, baseUrl: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parent.children().forEach { node ->
            when (node) {
                is Heading -> Text(markdownInline(node, baseUrl), style = when (node.level) {
                    1 -> MaterialTheme.typography.titleLarge
                    2 -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.titleSmall
                }, fontWeight = FontWeight.Bold)
                is MarkdownParagraph -> Text(markdownInline(node, baseUrl), style = MaterialTheme.typography.bodyMedium)
                is FencedCodeBlock -> MarkdownCode(node.literal)
                is IndentedCodeBlock -> MarkdownCode(node.literal)
                is ThematicBreak -> HorizontalDivider()
                is BlockQuote -> Row {
                    Box(Modifier.width(3.dp).heightIn(min = 24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Box(Modifier.padding(start = 10.dp)) { MarkdownBlocks(node, baseUrl) }
                }
                is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    node.children().forEachIndexed { index, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (node is OrderedList) "${(node.markerStartNumber ?: 1) + index}." else "•", style = MaterialTheme.typography.bodyMedium)
                            Box(Modifier.weight(1f)) { MarkdownBlocks(item, baseUrl) }
                        }
                    }
                }
                is HtmlBlock -> Text(android.text.Html.fromHtml(node.literal, android.text.Html.FROM_HTML_MODE_COMPACT).toString(),
                    style = MaterialTheme.typography.bodyMedium)
                else -> if (node.firstChild != null) MarkdownBlocks(node, baseUrl)
            }
        }
    }
}

@Composable private fun MarkdownCode(code: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(code.trimEnd(), Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun markdownInline(node: Node, baseUrl: String): AnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    return buildAnnotatedString {
        fun visit(child: Node) {
            when (child) {
                is MarkdownTextNode -> append(child.literal)
                is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(child.literal) }
                is SoftLineBreak -> append(' ')
                is HardLineBreak -> append('\n')
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { child.children().forEach(::visit) }
                is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { child.children().forEach(::visit) }
                is Link, is Image -> {
                    val destination = if (child is Link) child.destination else (child as Image).destination
                    val url = releaseNoteUrl(baseUrl, destination)
                    if (url != null) withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = color)))) {
                        child.children().forEach(::visit)
                        if (child.firstChild == null) append(destination)
                    } else child.children().forEach(::visit)
                }
                is HtmlInline -> Unit
                else -> child.children().forEach(::visit)
            }
        }
        node.children().forEach(::visit)
    }
}

internal fun releaseNoteUrl(base: String, destination: String): String? = runCatching {
    URI(base).resolve(destination).takeIf { it.scheme in setOf("https", "http") && it.host != null && it.userInfo == null }?.toASCIIString()
}.getOrNull()

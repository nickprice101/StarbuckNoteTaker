package com.example.starbucknotetaker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import com.example.starbucknotetaker.richtext.MarkdownRichText
import com.example.starbucknotetaker.richtext.RichTextDocument
import com.example.starbucknotetaker.richtext.RichTextStyle

/** Compose equivalent of a CSS citation chip for assistant Markdown links. */
@Composable
internal fun MarkdownCitationText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colors.onSurface,
    style: TextStyle = MaterialTheme.typography.body1,
) {
    val document = remember(markdown) { MarkdownRichText.parse(markdown) }
    CitationRichText(document = document, modifier = modifier, color = color, style = style)
}

/** Renders semantic link spans as rounded, tappable pills while preserving surrounding rich text. */
@Composable
internal fun CitationRichText(
    document: RichTextDocument,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colors.onSurface,
    style: TextStyle = MaterialTheme.typography.body1,
) {
    val uriHandler = LocalUriHandler.current
    val display = remember(document) { buildCitationDisplay(document) }
    val inlineContent = display.citations.associate { citation ->
        citation.id to InlineTextContent(
            placeholder = Placeholder(
                width = (citation.label.length * 0.61f + 1.8f).coerceAtMost(28f).em,
                height = 1.55f.em,
                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
            ),
        ) {
            CitationPill(
                citation = citation,
                onOpen = { runCatching { uriHandler.openUri(citation.url) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    Text(
        text = display.text,
        modifier = modifier,
        inlineContent = inlineContent,
        color = color,
        style = style,
    )
}

/** Citation chips shown beneath editable note and event text fields. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CitationPillRow(
    document: RichTextDocument,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val citations = remember(document) { buildCitationDisplay(document).citations.distinctBy { it.url } }
    if (citations.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        citations.forEach { citation ->
            CitationPill(
                citation = citation,
                onOpen = { runCatching { uriHandler.openUri(citation.url) } },
                modifier = Modifier.widthIn(max = 240.dp),
            )
        }
    }
}

@Composable
private fun CitationPill(
    citation: CitationInline,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onOpen),
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colors.primary.copy(alpha = 0.14f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = citation.label,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                color = MaterialTheme.colors.primary,
                style = MaterialTheme.typography.caption,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal data class CitationDisplay(
    val text: AnnotatedString,
    val citations: List<CitationInline>,
)

internal data class CitationInline(
    val id: String,
    val label: String,
    val url: String,
)

internal fun buildCitationDisplay(document: RichTextDocument): CitationDisplay {
    val candidates = document.spans.mapNotNull { range ->
        range.styles.filterIsInstance<RichTextStyle.Citation>().firstOrNull()?.let { citation ->
            CitationRange(range.start, range.end, citation.url)
        }
    }.sortedBy { it.start }
    val ranges: List<CitationRange> = buildList {
        candidates.forEach { range ->
            val previous = lastOrNull()
            if (previous == null || range.start >= previous.end) add(range)
        }
    }
    if (ranges.isEmpty()) return CitationDisplay(document.toAnnotatedString(), emptyList())

    val citations = mutableListOf<CitationInline>()
    val annotated = buildAnnotatedString {
        var cursor = 0
        ranges.forEachIndexed { index, range ->
            if (range.start > cursor) append(document.slice(cursor, range.start).toAnnotatedString())
            val label = document.text.substring(range.start, range.end).trim().ifBlank { "Source" }
            val id = "citation_$index"
            appendInlineContent(id, label)
            citations += CitationInline(id = id, label = label, url = range.url)
            cursor = range.end
        }
        if (cursor < document.text.length) append(document.slice(cursor, document.text.length).toAnnotatedString())
    }
    return CitationDisplay(text = annotated, citations = citations)
}

private data class CitationRange(val start: Int, val end: Int, val url: String)

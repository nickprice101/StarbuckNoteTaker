package com.example.starbucknotetaker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.example.starbucknotetaker.websiteName
import com.example.starbucknotetaker.richtext.MarkdownRichText
import com.example.starbucknotetaker.richtext.RichTextDocument
import com.example.starbucknotetaker.richtext.RichTextStyle
import java.net.URI
import java.util.Locale

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
                width = (citation.label.length * 0.52f + 0.9f).coerceAtMost(12f).em,
                height = 1.2f.em,
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
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        citations.forEach { citation ->
            CitationPill(
                citation = citation,
                onOpen = { runCatching { uriHandler.openUri(citation.url) } },
                modifier = Modifier.widthIn(max = 160.dp),
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
    val siteStyle = remember(citation.url) { citationSiteStyle(citation.url) }
    val favicon = remember(citation.url) { faviconUrl(citation.url) }
    val faviconPainter = rememberAsyncImagePainter(favicon)
    Surface(
        modifier = modifier.clickable(onClick = onOpen),
        shape = RoundedCornerShape(percent = 50),
        color = siteStyle.background,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        ) {
            if (favicon != null && faviconPainter.state is AsyncImagePainter.State.Success) {
                Image(
                    painter = faviconPainter,
                    contentDescription = citation.label,
                    modifier = Modifier.size(12.dp),
                )
            } else {
                Text(
                    text = citation.label,
                    color = siteStyle.foreground,
                    style = MaterialTheme.typography.caption.copy(
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

internal data class CitationSiteStyle(
    val background: Color,
    val foreground: Color,
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
            val label = websiteName(range.url)
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

/** Uses the conventional same-origin favicon, falling back to the website label if unavailable. */
internal fun faviconUrl(url: String): String? = runCatching {
    val uri = URI(url)
    val scheme = uri.scheme?.lowercase(Locale.US)
    val authority = uri.rawAuthority
    if (scheme !in setOf("http", "https") || authority.isNullOrBlank()) {
        null
    } else {
        "$scheme://$authority/favicon.ico"
    }
}.getOrNull()

/** Uses recognizable site colors, with a stable domain-specific palette for other websites. */
internal fun citationSiteStyle(url: String): CitationSiteStyle {
    val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.US) }
        .getOrDefault("")
        .removePrefix("www.")
    val background = when {
        host.matchesDomain("wikipedia.org") -> Color(0xFF202122)
        host.matchesDomain("nasa.gov") -> Color(0xFF0B3D91)
        host.matchesDomain("youtube.com") || host == "youtu.be" -> Color(0xFFFF0000)
        host.matchesDomain("reddit.com") -> Color(0xFFFF4500)
        host.matchesDomain("github.com") -> Color(0xFF24292F)
        host.matchesDomain("stackoverflow.com") -> Color(0xFFF48024)
        host.matchesDomain("bbc.co.uk") || host.matchesDomain("bbc.com") -> Color(0xFFB80000)
        host.matchesDomain("reuters.com") -> Color(0xFFFF8000)
        host.matchesDomain("nytimes.com") -> Color(0xFF121212)
        host.matchesDomain("theguardian.com") -> Color(0xFF052962)
        host.matchesDomain("microsoft.com") -> Color(0xFF0067B8)
        host.matchesDomain("google.com") -> Color(0xFF4285F4)
        host.matchesDomain("apple.com") -> Color(0xFF1D1D1F)
        host.matchesDomain("arxiv.org") -> Color(0xFFB31B1B)
        host.matchesDomain("who.int") -> Color(0xFF007EB4)
        host.matchesDomain("gov.uk") -> Color(0xFF1D70B8)
        else -> GENERIC_SITE_COLORS[(host.hashCode().ushr(1) % GENERIC_SITE_COLORS.size)]
    }
    val foreground = if (host.matchesDomain("reuters.com")) Color(0xFF1A1A1A) else Color.White
    return CitationSiteStyle(background = background, foreground = foreground)
}

private fun String.matchesDomain(domain: String): Boolean = this == domain || endsWith(".$domain")

private val GENERIC_SITE_COLORS = listOf(
    Color(0xFF1565C0),
    Color(0xFF00695C),
    Color(0xFF6A1B9A),
    Color(0xFFAD1457),
    Color(0xFF455A64),
    Color(0xFF283593),
)

package net.sp00nz.deadzone

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.SAXParserFactory

/**
 * Parsing podcast XML. Pure — no Android imports, so it runs under plain JUnit.
 *
 * ponytail: SAX from javax.xml, not XmlPullParser. Same code path on the device and
 * in a unit test, and no dependency. XmlPullParser would have needed Robolectric to
 * test at all, which is a heavier price than this handler.
 */

data class ParsedItem(
    val guid: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val published: Long,   // epoch millis, 0 if the feed gave us nothing usable
    val duration: Int,     // seconds, 0 if unknown
    val size: Long,        // bytes, 0 if unknown
    val image: String?,
    val chaptersUrl: String? = null,     // podcast:chapters, a JSON document
    val transcriptUrl: String? = null,   // podcast:transcript
    val transcriptType: String? = null,  // text/vtt, application/srt, ...
)

data class ParsedFeed(
    val title: String,
    val image: String?,
    val category: String?,
    val items: List<ParsedItem>,
)

data class OpmlEntry(val title: String, val url: String, val category: String?)

/** One feed in an OPML file, plus the folder it sat in. */
fun parseOpml(xml: String): List<OpmlEntry> {
    val out = mutableListOf<OpmlEntry>()
    // null marks a feed outline rather than a folder — a magic string here would
    // one day collide with somebody's actual folder name.
    val folders = ArrayDeque<String?>()
    parse(xml, object : DefaultHandler() {
        override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
            if (!q.equals("outline", true)) return
            val url = a.attr("xmlUrl")
            val text = a.attr("text") ?: a.attr("title") ?: ""
            if (url.isNullOrBlank()) {
                // A folder outline: no xmlUrl, and its children inherit the name.
                folders.addLast(text)
            } else {
                out += OpmlEntry(
                    text.ifBlank { url },
                    url,
                    folders.lastOrNull { it != null }?.ifBlank { null },
                )
                // Feed outlines are usually empty elements, but endElement fires for
                // them too, so push a marker to keep the pops balanced.
                folders.addLast(null)
            }
        }

        override fun endElement(u: String?, l: String?, q: String) {
            if (q.equals("outline", true)) folders.removeLastOrNull()
        }
    })
    return out
}

fun writeOpml(feeds: List<OpmlEntry>): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n")
    append("  <head><title>Deadzone</title></head>\n  <body>\n")
    for ((category, group) in feeds.groupBy { it.category }) {
        val indent = if (category != null) {
            append("    <outline text=\"" + esc(category) + "\">\n"); "      "
        } else "    "
        for (f in group) {
            append(indent + "<outline type=\"rss\" text=\"" + esc(f.title) +
                "\" xmlUrl=\"" + esc(f.url) + "\"/>\n")
        }
        if (category != null) append("    </outline>\n")
    }
    append("  </body>\n</opml>\n")
}

/** RSS 2.0 and Atom, whichever the feed turns out to be. */
fun parseFeed(xml: String): ParsedFeed {
    var feedTitle = ""
    var feedImage: String? = null
    var category: String? = null
    val items = mutableListOf<ParsedItem>()

    var inItem = false
    var inChannelImage = false
    val text = StringBuilder()

    // Per-item scratch, reset on every <item>.
    var guid = ""; var title = ""; var desc = ""; var audio = ""
    var published = 0L; var duration = 0; var size = 0L; var image: String? = null
    var chapters: String? = null
    var transcript: String? = null; var transcriptType: String? = null

    parse(xml, object : DefaultHandler() {
        override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
            text.setLength(0)
            when (strip(q)) {
                "item", "entry" -> {
                    inItem = true
                    guid = ""; title = ""; desc = ""; audio = ""
                    published = 0L; duration = 0; size = 0L; image = null
                    chapters = null; transcript = null; transcriptType = null
                }
                // The podcast: namespace. Both are just URLs on the element.
                "chapters" -> if (inItem) a.attr("url")?.let { chapters = it }
                "transcript" -> if (inItem) {
                    val url = a.attr("url")
                    val type = a.attr("type").orEmpty()
                    // A show often publishes the same transcript three ways. VTT
                    // carries timings and parses in a dozen lines; HTML does not.
                    val better = transcript == null ||
                        (transcriptType?.contains("vtt") != true && type.contains("vtt"))
                    if (url != null && better) { transcript = url; transcriptType = type }
                }
                "enclosure" -> {
                    // RSS. Some feeds carry several; the first is the episode.
                    val url = a.attr("url")
                    if (url != null && audio.isEmpty()) {
                        audio = url
                        size = a.attr("length")?.toLongOrNull() ?: 0L
                    }
                }
                "link" -> {
                    // Atom's enclosure.
                    if (a.attr("rel") == "enclosure") {
                        val href = a.attr("href")
                        if (href != null && audio.isEmpty()) {
                            audio = href
                            size = a.attr("length")?.toLongOrNull() ?: 0L
                        }
                    }
                }
                "image" -> {
                    // <itunes:image href> carries the URL; RSS <image> wraps a <url> child.
                    val href = a.attr("href")
                    if (href != null) {
                        if (inItem) image = href else if (feedImage == null) feedImage = href
                    } else if (!inItem) inChannelImage = true
                }
                "thumbnail" -> {
                    val url = a.attr("url")
                    if (inItem && image == null && url != null) image = url
                }
                "category" -> {
                    // <itunes:category text="..."> at channel level is the one worth keeping.
                    if (!inItem && category == null) category = a.attr("text")
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.appendRange(ch, start, start + length)
        }

        override fun endElement(u: String?, l: String?, q: String) {
            val body = text.toString().trim()
            when (strip(q)) {
                "item", "entry" -> {
                    inItem = false
                    // No audio means it isn't an episode — a text post in the feed, or
                    // some channel-level junk shaped like an item. Dropping it is right.
                    if (audio.isNotEmpty()) {
                        items += ParsedItem(
                            guid = guid.ifBlank { audio },
                            title = title.ifBlank { "(untitled)" },
                            description = desc, audioUrl = audio,
                            published = published, duration = duration,
                            size = size, image = image,
                            chaptersUrl = chapters,
                            transcriptUrl = transcript, transcriptType = transcriptType,
                        )
                    }
                }
                "image" -> inChannelImage = false
                "url" -> if (inChannelImage && feedImage == null) feedImage = body
                "title" ->
                    if (inItem) { if (title.isEmpty()) title = body }
                    else if (feedTitle.isEmpty()) feedTitle = body
                "guid", "id" -> if (inItem && guid.isEmpty()) guid = body
                "pubdate", "published", "updated", "date" ->
                    if (inItem && published == 0L) published = parseDate(body)
                "duration" -> if (inItem && duration == 0) duration = parseDuration(body)
                "description", "summary", "encoded", "subtitle", "content" ->
                    if (inItem && desc.isEmpty()) desc = body
            }
            text.setLength(0)
        }
    })

    return ParsedFeed(feedTitle, feedImage, category, items)
}

/** itunes:duration is seconds, or M:SS, or H:MM:SS, depending on who published it. */
fun parseDuration(s: String): Int {
    val parts = s.trim().split(":")
    if (parts.any { it.isBlank() }) return 0
    return try {
        when (parts.size) {
            1 -> parts[0].toDouble().toInt()
            2 -> parts[0].toInt() * 60 + parts[1].toDouble().toInt()
            3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toDouble().toInt()
            else -> 0
        }
    } catch (_: NumberFormatException) {
        0
    }
}

// RFC 1123 is what RSS is supposed to use and what almost nobody emits exactly. The
// strict java.time parser rejects a single-digit day, a missing seconds field, or a
// "UT" zone, all of which appear in feeds that have been running since 2005. Sorting
// a library by a date that silently came back 0 is worse than trying eight patterns.
private val DATE_PATTERNS = listOf(
    "EEE, d MMM yyyy HH:mm:ss zzz",
    "EEE, d MMM yyyy HH:mm:ss Z",
    "EEE, d MMM yyyy HH:mm zzz",
    "d MMM yyyy HH:mm:ss zzz",
    "yyyy-MM-dd'T'HH:mm:ssXXX",
    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd",
)

fun parseDate(s: String): Long {
    val t = s.trim().replace(" UT", " GMT")
    if (t.isEmpty()) return 0L
    for (p in DATE_PATTERNS) {
        try {
            val f = SimpleDateFormat(p, Locale.US)
            if (!p.contains("zzz") && !p.contains("Z") && !p.contains("X")) {
                f.timeZone = TimeZone.getTimeZone("UTC")
            }
            return f.parse(t)?.time ?: continue
        } catch (_: Exception) {
            // try the next shape
        }
    }
    return 0L
}

data class Cue(val startMs: Long, val text: String)

/**
 * WebVTT and SRT, which differ by a decimal comma, an optional index line, and a
 * header. Parsing both with one function is less code than deciding which it is.
 *
 *     00:01:23.456 --> 00:01:25.000
 *     the words
 */
fun parseCues(body: String): List<Cue> {
    val out = mutableListOf<Cue>()
    var start = -1L
    val text = StringBuilder()

    fun flush() {
        if (start >= 0 && text.isNotBlank()) out += Cue(start, text.toString().trim())
        start = -1L; text.setLength(0)
    }

    for (raw in body.lineSequence()) {
        val line = raw.trim()
        val arrow = line.indexOf("-->")
        when {
            arrow > 0 -> {
                // A new timing line ends the previous cue, whether or not a blank
                // line separated them — plenty of transcripts omit it.
                flush()
                start = cueTime(line.substring(0, arrow).trim())
            }
            line.isEmpty() -> flush()
            // The bare number above an SRT cue, and VTT's header, are not content.
            start < 0 -> Unit
            else -> {
                if (text.isNotEmpty()) text.append(' ')
                text.append(stripHtml(line))
            }
        }
    }
    flush()
    return out
}

/** "00:01:23.456", "01:23.456" or "00:01:23,456" -> millis. */
private fun cueTime(s: String): Long {
    val t = s.substringBefore(' ').replace(',', '.')
    val parts = t.split(':')
    if (parts.isEmpty() || parts.size > 3) return -1L
    return try {
        // Fold in base 60 as seconds, then convert once. Scaling each part to millis
        // as you go multiplies the already-scaled accumulator by 60,000 a second time.
        var secs = 0.0
        for (p in parts) secs = secs * 60 + p.toDouble()
        (secs * 1000).toLong()
    } catch (_: NumberFormatException) {
        -1L
    }
}

/** Descriptions are HTML. Lists and search results want words. */
fun stripHtml(s: String): String =
    s.replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ").trim()

private fun Attributes.attr(name: String): String? {
    for (i in 0 until length) {
        val q = getQName(i)
        if (q.equals(name, true) || q.substringAfter(':').equals(name, true)) {
            return getValue(i).takeIf { it.isNotBlank() }
        }
    }
    return null
}

/** "itunes:duration" -> "duration". Feeds use whatever prefix they feel like. */
private fun strip(q: String) = q.substringAfter(':').lowercase()

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;")

private fun parse(xml: String, handler: DefaultHandler) {
    val f = SAXParserFactory.newInstance()
    f.isNamespaceAware = false

    // This XML came off the open internet, so external entities must not resolve.
    //
    // Feature names are not portable: the JVM's Xerces knows disallow-doctype-decl,
    // Android's Expat does not and *throws* SAXNotRecognizedException rather than
    // ignoring it — which turned this hardening into a crash on every single feed on
    // a real device while unit tests on the JVM stayed green. So each switch is
    // attempted on its own and a refusal is not fatal.
    for (feature in listOf(
        "http://apache.org/xml/features/disallow-doctype-decl",
        "http://xml.org/sax/features/external-general-entities",
        "http://xml.org/sax/features/external-parameter-entities",
    )) {
        runCatching { f.setFeature(feature, feature.endsWith("disallow-doctype-decl")) }
    }

    val reader = f.newSAXParser().xmlReader
    reader.contentHandler = handler
    reader.errorHandler = handler
    // The portable stop, and the one that actually holds on Android: whatever the
    // parser will or won't let us switch off, an entity resolver that hands back
    // nothing means no feed can read a local file or make us fetch a URL.
    reader.entityResolver = EntityResolver { _, _ -> InputSource(StringReader("")) }
    reader.parse(InputSource(StringReader(xml.trim())))
}

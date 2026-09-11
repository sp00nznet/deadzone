package net.sp00nz.deadzone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parser is the only thing here that can quietly ruin a library: a date that
 * comes back 0 sorts a show's whole back catalogue into 1970, and a missed enclosure
 * makes an episode invisible. So it gets the tests, and the build gates on them.
 */
class FeedTest {

    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>Darknet Diaries</title>
            <itunes:image href="https://example.com/art.jpg"/>
            <itunes:category text="Technology"/>
            <item>
              <title>Ep 1: The Phreaky World of PBX Hacking</title>
              <guid isPermaLink="false">dd-0001</guid>
              <pubDate>Tue, 5 Sep 2017 04:00:00 GMT</pubDate>
              <itunes:duration>28:11</itunes:duration>
              <description>&lt;p&gt;Phone &amp;amp; phreaks.&lt;/p&gt;</description>
              <enclosure url="https://cdn.example.com/1.mp3" length="13643505" type="audio/mpeg"/>
            </item>
            <item>
              <title>A post with no audio</title>
              <guid>dd-note</guid>
              <pubDate>Wed, 06 Sep 2017 04:00:00 GMT</pubDate>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `reads a normal rss feed`() {
        val f = parseFeed(rss)
        assertEquals("Darknet Diaries", f.title)
        assertEquals("https://example.com/art.jpg", f.image)
        assertEquals("Technology", f.category)

        // The second <item> has no enclosure, so it is not an episode.
        assertEquals(1, f.items.size)
        val e = f.items[0]
        assertEquals("dd-0001", e.guid)
        assertEquals("https://cdn.example.com/1.mp3", e.audioUrl)
        assertEquals(13643505L, e.size)
        assertEquals(28 * 60 + 11, e.duration)
        assertTrue(e.published > 0, "pubDate must parse or the feed sorts into 1970")
        assertTrue(e.description.contains("Phone"))
    }

    @Test
    fun `channel image from the rss url child, not just itunes href`() {
        val f = parseFeed(
            """<rss><channel><title>X</title><image><url>https://a/b.png</url></image>
               <item><title>E</title><enclosure url="https://a/1.mp3"/></item>
               </channel></rss>"""
        )
        assertEquals("https://a/b.png", f.image)
    }

    @Test
    fun `an item with no guid falls back to the audio url`() {
        val f = parseFeed(
            """<rss><channel><item><title>E</title>
               <enclosure url="https://a/1.mp3"/></item></channel></rss>"""
        )
        assertEquals("https://a/1.mp3", f.items[0].guid)
    }

    @Test
    fun `atom entries with an enclosure link`() {
        val f = parseFeed(
            """<feed xmlns="http://www.w3.org/2005/Atom">
                 <title>Atom Show</title>
                 <entry>
                   <title>Atom Ep</title>
                   <id>urn:uuid:1</id>
                   <published>2021-03-04T10:00:00Z</published>
                   <link rel="enclosure" href="https://a/atom.mp3" length="99"/>
                 </entry>
               </feed>"""
        )
        assertEquals("Atom Show", f.title)
        assertEquals(1, f.items.size)
        assertEquals("https://a/atom.mp3", f.items[0].audioUrl)
        assertEquals(99L, f.items[0].size)
        assertTrue(f.items[0].published > 0)
    }

    @Test
    fun `an external entity in a feed never resolves`() {
        // XXE: a feed off the open internet must never be able to read the phone's
        // disk. This asserts the *outcome*, not the mechanism, because the mechanism
        // differs by platform: the JVM refuses the DTD outright, while Android's
        // Expat parser does not support that switch and is stopped by the entity
        // resolver instead. Either way the file contents must not appear.
        val hostile = """<!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/hosts">]>
                         <rss><channel><title>&x;</title>
                         <item><title>E</title><enclosure url="https://a/1.mp3"/></item>
                         </channel></rss>"""
        val title = runCatching { parseFeed(hostile).title }.getOrDefault("")
        assertTrue(title.isBlank(), "external entity leaked into the feed: '$title'")
    }

    // ---- dates ----

    @Test
    fun `the shapes real feeds actually emit`() {
        // Every one of these appears in the wild; the strict RFC_1123 parser
        // rejects all but the first two.
        for (s in listOf(
            "Tue, 05 Sep 2017 04:00:00 GMT",
            "Tue, 5 Sep 2017 04:00:00 -0400",
            "Sat, 01 Jan 2022 10:30 EST",
            "5 Sep 2017 04:00:00 GMT",
            "Mon, 12 Jun 2006 04:00:00 UT",
            "2021-03-04T10:00:00Z",
            "2021-03-04T10:00:00.000Z",
            "2021-03-04",
        )) {
            assertTrue(parseDate(s) > 0, "failed to parse: $s")
        }
        assertEquals(0L, parseDate("not a date at all"))
        assertEquals(0L, parseDate(""))
    }

    @Test
    fun `durations in every unit itunes allows`() {
        assertEquals(3600, parseDuration("3600"))
        assertEquals(191, parseDuration("3:11"))
        assertEquals(3671, parseDuration("1:01:11"))
        assertEquals(3671, parseDuration(" 1:01:11 "))
        assertEquals(0, parseDuration(""))
        assertEquals(0, parseDuration("about an hour"))
        assertEquals(0, parseDuration("1::11"))
    }

    // ---- opml ----

    @Test
    fun `opml folders become categories and survive a round trip`() {
        val xml = """
            <opml version="2.0"><body>
              <outline text="Politics">
                <outline type="rss" text="TrueAnon" xmlUrl="https://a/ta.xml"/>
                <outline type="rss" text="Chapo" xmlUrl="https://a/cth.xml"/>
              </outline>
              <outline type="rss" text="Darknet Diaries" xmlUrl="https://a/dd.xml"/>
            </body></opml>
        """.trimIndent()

        val feeds = parseOpml(xml)
        assertEquals(3, feeds.size)
        assertEquals("Politics", feeds[0].category)
        assertEquals("Politics", feeds[1].category)
        // The third sits outside the folder — the folder must have been popped.
        assertNull(feeds[2].category)
        assertEquals("https://a/dd.xml", feeds[2].url)

        val again = parseOpml(writeOpml(feeds))
        assertEquals(feeds.toSet(), again.toSet())
    }

    @Test
    fun `opml with an ampersand in a title survives the round trip`() {
        val feeds = listOf(OpmlEntry("Wait Wait & Co <live>", "https://a/w.xml?x=1&y=2", null))
        assertEquals(feeds, parseOpml(writeOpml(feeds)))
    }

    // ---- search ----

    @Test
    fun `fts input is quoted so punctuation is a search, not a syntax error`() {
        // A bare apostrophe or a leading dash is an FTS5 syntax error, and the user
        // typing one must get results rather than a crash.
        assertEquals("don t*", ftsQuery("don't"))
        // Case is left alone — the tokenizer folds it at match time.
        assertEquals("NATO*", ftsQuery("  -NATO  "))
        assertEquals("Mt Gox*", ftsQuery("Mt. Gox"))
        // Nothing survives that MATCH could read as an operator.
        assertTrue(ftsQuery("a\"b -c* (d)")!!.none { it in "\"()-" })
        assertNull(ftsQuery("   "))
        assertNull(ftsQuery("!!!"))
    }

    @Test
    fun `html in show notes is reduced to words`() {
        assertEquals(
            "Phone & phreaks. Read more",
            stripHtml("<p>Phone &amp; phreaks.</p>\n<a href='#'>Read  more</a>"),
        )
    }
}

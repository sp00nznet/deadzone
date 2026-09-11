package net.sp00nz.deadzone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The folder-import matcher. Getting this wrong attaches the wrong audio to an
 * episode, which looks like the app working right up until you press play, so it is
 * worth pinning the awkward cases down.
 */
class MatchTest {

    private fun episodes(vararg titles: String) =
        titles.mapIndexed { i, t -> Candidate(t, i.toLong()) }

    @Test
    fun `ripper filenames find publisher titles`() {
        // Real shapes: a leading index the feed never had, "Episode" spelled out,
        // punctuation that does not survive a filesystem.
        val feed = episodes(
            "Ep 7: Manfred (Part 1)",
            "Episode 559: Deadpool",
            "9: The Rise and Fall of Mt. Gox",
        )
        val m = matchAll(
            listOf(
                "0007 - 7 Manfred (Part 1)",
                "0617 - Episode 559 Deadpool",
                "0009 - 9 The Rise and Fall of Mt Gox",
            ),
            feed,
        )
        assertEquals(0L, m["0007 - 7 Manfred (Part 1)"])
        assertEquals(1L, m["0617 - Episode 559 Deadpool"])
        assertEquals(2L, m["0009 - 9 The Rise and Fall of Mt Gox"])
    }

    @Test
    fun `an episode is never handed to two files`() {
        val feed = episodes("The Bone Wars", "The Bone Wars Revisited")
        val m = matchAll(listOf("The Bone Wars", "The Bone Wars Revisited"), feed)
        assertEquals(2, m.size)
        assertEquals(m.values.toSet().size, m.size, "two files claimed the same episode")
    }

    @Test
    fun `the better match wins the episode regardless of file order`() {
        // "Spolia" scores partially against "Spolia Revisited"; if files are handed
        // episodes in file order it takes it, and the exact match is left with nothing.
        val feed = episodes("Spolia Revisited")
        val m = matchAll(listOf("Spolia", "Spolia Revisited"), feed)
        assertEquals(1, m.size)
        assertEquals("Spolia Revisited", m.keys.first())
    }

    @Test
    fun `unrelated audio is left alone`() {
        val feed = episodes("The Rise and Fall of Mt. Gox", "Carna Botnet")
        assertTrue(matchAll(listOf("track01", "Ringtone", "holiday voicemail"), feed).isEmpty())
    }

    @Test
    fun `agreeing on a number alone is not a match`() {
        // Every show has an "Episode 12". Matching on the digit across a whole
        // library is how a collection gets quietly scrambled, so a shared word is
        // required before a number counts for anything.
        val feed = episodes("Episode 12", "Episode 13")
        assertTrue(matchAll(listOf("0012 - Episode 12"), feed).isEmpty())
    }

    @Test
    fun `part one and part two stay different episodes`() {
        val feed = episodes("Manfred (Part 1)", "Manfred (Part 2)")
        val m = matchAll(listOf("0007 - 7 Manfred (Part 1)", "0008 - 8 Manfred (Part 2)"), feed)
        assertEquals(0L, m["0007 - 7 Manfred (Part 1)"])
        assertEquals(1L, m["0008 - 8 Manfred (Part 2)"])
    }

    @Test
    fun `accents and case do not matter`() {
        val feed = episodes("Beyoncé and the Album Rollout")
        val m = matchAll(listOf("0042 - BEYONCE AND THE ALBUM ROLLOUT"), feed)
        assertEquals(0L, m.values.firstOrNull())
    }

    @Test
    fun `empty input on either side is not a crash`() {
        assertTrue(matchAll(emptyList(), episodes("A Title")).isEmpty())
        assertTrue(matchAll(listOf("a file"), emptyList<Candidate<Long>>()).isEmpty())
    }

    @Test
    fun `normalise strips the index, the filler and the punctuation`() {
        // Rippers write the index twice ("0007 - 7 ..."); both go, but the "1" of
        // "Part 1" stays, because that one is part of the episode's identity.
        assertEquals("manfred 1", normalise("0007 - 7 Manfred (Part 1)"))
        assertEquals("559 deadpool", normalise("Episode 559: Deadpool"))
        assertEquals("9 the rise and fall of mt gox", normalise("9: The Rise and Fall of Mt. Gox"))
    }

    // ---- transcripts ----

    @Test
    fun `webvtt cues carry the right times`() {
        val cues = parseCues(
            """
            WEBVTT

            00:00:01.500 --> 00:00:04.000
            Welcome back.

            00:01:23.456 --> 00:01:25.000
            The second line
            wrapped over two.
            """.trimIndent()
        )
        assertEquals(2, cues.size)
        assertEquals(1500L, cues[0].startMs)
        assertEquals("Welcome back.", cues[0].text)
        // 1m23.456s. Folding in base 60 *then* scaling, not scaling as you go —
        // the latter silently multiplies by 60,000 twice.
        assertEquals(83_456L, cues[1].startMs)
        assertEquals("The second line wrapped over two.", cues[1].text)
    }

    @Test
    fun `srt with its index lines and decimal commas`() {
        val cues = parseCues(
            """
            1
            00:00:00,000 --> 00:00:02,000
            First.

            2
            00:00:02,000 --> 00:00:04,000
            Second.
            """.trimIndent()
        )
        assertEquals(2, cues.size)
        assertEquals(0L, cues[0].startMs)
        assertEquals(2000L, cues[1].startMs)
        assertEquals("First.", cues[0].text)
    }

    @Test
    fun `a missing blank line between cues still separates them`() {
        val cues = parseCues(
            "00:00:01.000 --> 00:00:02.000\nOne.\n00:00:03.000 --> 00:00:04.000\nTwo."
        )
        assertEquals(2, cues.size)
        assertEquals("One.", cues[0].text)
        assertEquals(3000L, cues[1].startMs)
    }

    @Test
    fun `mm ss timings without an hours field`() {
        assertEquals(90_000L, parseCues("01:30.000 --> 01:32.000\nx").first().startMs)
    }

    @Test
    fun `junk is not a crash`() {
        assertTrue(parseCues("").isEmpty())
        assertTrue(parseCues("not a transcript at all").isEmpty())
        assertTrue(parseCues("aa:bb:cc --> dd\nwords").isEmpty())
    }

    // ---- podcast namespace ----

    @Test
    fun `chapters and transcripts come off the item`() {
        val f = parseFeed(
            """<rss xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel>
                 <item>
                   <title>E</title>
                   <enclosure url="https://a/1.mp3"/>
                   <podcast:chapters url="https://a/1.json" type="application/json+chapters"/>
                   <podcast:transcript url="https://a/1.html" type="text/html"/>
                   <podcast:transcript url="https://a/1.vtt" type="text/vtt"/>
                 </item>
               </channel></rss>"""
        )
        val e = f.items.single()
        assertEquals("https://a/1.json", e.chaptersUrl)
        // A show often publishes the same transcript three ways; VTT is the one with
        // timings, so it wins even though HTML came first.
        assertEquals("https://a/1.vtt", e.transcriptUrl)
        assertTrue(e.transcriptType!!.contains("vtt"))
    }

    @Test
    fun `a feed without the podcast namespace leaves them null`() {
        val e = parseFeed(
            """<rss><channel><item><title>E</title>
               <enclosure url="https://a/1.mp3"/></item></channel></rss>"""
        ).items.single()
        assertNull(e.chaptersUrl)
        assertNull(e.transcriptUrl)
    }
}

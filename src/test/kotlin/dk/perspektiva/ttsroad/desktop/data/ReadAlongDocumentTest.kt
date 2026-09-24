package dk.perspektiva.ttsroad.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadAlongDocumentTest {
    private fun response(
        text: String = "The knight rode north. Snow fell.",
        hasTimings: Boolean = true,
        paragraphs: List<List<Double>> = listOf(listOf(0.0, text.codePointCount(0, text.length).toDouble())),
        cues: List<List<Double>> = listOf(
            listOf(0.0, 3.0, 0.0),
            listOf(4.0, 10.0, 0.4),
            listOf(11.0, 15.0, 0.9),
            listOf(16.0, 21.0, 1.3),
            listOf(23.0, 27.0, 2.0),
        ),
    ) = ReadAlongResponse(
        chapter = ReadAlongChapter(10, 7, "Chapter 1", 1.0, 3.0, hasTimings, 1),
        text = text,
        paragraphs = paragraphs,
        cues = cues,
    )

    @Test
    fun `wire spans become a timed document with sentence and paragraph lookup`() {
        val document = ReadAlongDocument.from(response())

        assertEquals(ReadAlongTimingState.Timed, document.timingState)
        assertTrue(document.hasReliableTimings)
        assertEquals("knight", document.textIn(document.cues[1].span))
        assertEquals(0, document.paragraphIndexAt(24))
        assertEquals(TextSpan(0, 22), document.sentences[0])
    }

    @Test
    fun `cue lookup uses exact media boundaries and works backwards after a seek`() {
        val document = ReadAlongDocument.from(response())

        assertEquals(0, document.highlightAtMillis(0).cueIndex)
        assertEquals(1, document.highlightAtMillis(400).cueIndex)
        assertEquals(4, document.highlightAtMillis(2_900).cueIndex)
        assertEquals(1, document.highlightAtMillis(401).cueIndex)
        assertEquals(ReadAlongHighlight.None, document.highlightAtMillis(-1))
    }

    @Test
    fun `two-times playback still highlights from reported media position`() {
        val document = ReadAlongDocument.from(response())

        // At 2x these positions arrive in half the wall-clock time; lookup deliberately has no rate math.
        assertEquals(listOf(0, 2, 4), listOf(0L, 900L, 2_000L).map { document.highlightAtMillis(it).cueIndex })
    }

    @Test
    fun `only a click inside a timed word seeks`() {
        val document = ReadAlongDocument.from(response())

        assertEquals(0.4, document.seekSecondsForOffset(7))
        assertNull(document.seekSecondsForOffset(21))
        assertNull(document.seekSecondsForOffset(22))
        assertNull(document.seekSecondsForOffset(-1))
    }

    @Test
    fun `plain narration remains readable and never invents a seek`() {
        val document = ReadAlongDocument.from(response(hasTimings = false, cues = emptyList()))

        assertEquals(ReadAlongTimingState.TextOnly, document.timingState)
        assertFalse(document.hasReliableTimings)
        assertNull(document.seekSecondsForOffset(7))
        assertEquals(ReadAlongHighlight.None, document.highlightAtMillis(1_000))
    }

    @Test
    fun `malformed or non-monotonic cues disable all confident highlighting`() {
        val document = ReadAlongDocument.from(
            response(
                cues = listOf(
                    listOf(4.0, 10.0, 0.4),
                    listOf(0.0, 3.0, 0.8),
                    listOf(11.0, 15.0, Double.NaN),
                ),
            ),
        )

        assertEquals(ReadAlongTimingState.Malformed, document.timingState)
        assertTrue(document.cues.isEmpty())
        assertEquals(ReadAlongHighlight.None, document.highlightAtMillis(1_000))
    }

    @Test
    fun `missing paragraph ranges fall back to blank-line separated paragraphs`() {
        val document = ReadAlongDocument.from(
            response(
                text = "First paragraph.\n\nSecond paragraph.",
                hasTimings = false,
                paragraphs = emptyList(),
                cues = emptyList(),
            ),
        )

        assertEquals(2, document.paragraphs.size)
        assertEquals("Second paragraph.", document.textIn(document.paragraphs[1]))
    }

    @Test
    fun `duration mismatch rejects stale timings but tolerates normal encoder rounding`() {
        assertTrue(readAlongTimingsMatch(100.0, 103_000))
        assertFalse(readAlongTimingsMatch(100.0, 106_000))
        assertTrue(readAlongTimingsMatch(0.0, 90_000))
    }

    @Test
    fun `find returns every case-insensitive occurrence without overlap`() {
        assertEquals(
            listOf(TextSpan(0, 4), TextSpan(5, 9), TextSpan(10, 14)),
            readAlongMatches("Snow snow SNOW", "snow"),
        )
        assertTrue(readAlongMatches("chapter", "  ").isEmpty())
    }

    @Test
    fun `code point spans become UTF16 once without normalizing combining characters`() {
        val raw = response(
            text = "\uD83D\uDE00 e\u0301\n\n\uD840\uDC00 end.",
            paragraphs = listOf(listOf(0.0, 4.0), listOf(6.0, 12.0)),
            cues = listOf(
                listOf(0.0, 1.0, 0.0),
                listOf(2.0, 4.0, 0.5),
                listOf(6.0, 7.0, 1.0),
                listOf(8.0, 11.0, 1.5),
            ),
        )
        val document = ReadAlongDocument.from(raw)

        assertEquals(listOf(TextSpan(0, 5), TextSpan(7, 14)), document.paragraphs)
        assertEquals(listOf(TextSpan(0, 2), TextSpan(3, 5), TextSpan(7, 9), TextSpan(10, 13)), document.cues.map { it.span })
        assertEquals(listOf("\uD83D\uDE00", "e\u0301", "\uD840\uDC00", "end"), document.cues.map { document.textIn(it.span) })
        assertEquals(TextSpan(7, 9), document.highlightAtMillis(1000).word)
        assertEquals(1, document.paragraphIndexAt(8))
        assertEquals(1.0, document.seekSecondsForOffset(8))
        assertEquals(1.5, document.seekSecondsForOffset(12))
        assertEquals(listOf(6.0, 7.0, 1.0), raw.cues[2])
        assertEquals(document, ReadAlongDocument.from(raw))
    }

    @Test
    fun `invalid offsets are rejected rather than truncated or clamped`() {
        val invalid = listOf(
            listOf(-1.0, 3.0, 0.0),
            listOf(0.5, 3.0, 0.0),
            listOf(0.0, 3.5, 0.0),
            listOf(0.0, 34.0, 0.0),
            listOf(0.0, Double.MAX_VALUE, 0.0),
            listOf(Double.NaN, 3.0, 0.0),
            listOf(0.0, Double.POSITIVE_INFINITY, 0.0),
            listOf(3.0, 3.0, 0.0),
            listOf(4.0, 3.0, 0.0),
            listOf(0.0, 3.0),
            listOf(0.0, 3.0, 0.0, 1.0),
        )
        invalid.forEach { row ->
            val document = ReadAlongDocument.from(response(cues = listOf(row)))
            assertEquals(ReadAlongTimingState.Malformed, document.timingState, row.toString())
            assertEquals(ReadAlongHighlight.None, document.highlightAtMillis(1000))
            assertNull(document.seekMillisForOffset(1))
            assertEquals("The knight rode north. Snow fell.", document.textIn(document.paragraphs.single()))
        }
        val surrogateUnits = ReadAlongDocument.from(response(text = "\uD840\uDC00", cues = listOf(listOf(0.0, 2.0, 0.0))))
        assertEquals(ReadAlongTimingState.Malformed, surrogateUnits.timingState)
    }

    @Test
    fun `incomplete overlapping fractional or unordered paragraphs derive all prose`() {
        val invalid = listOf(
            listOf(listOf(0.0, 5.0)),
            listOf(listOf(7.0, 13.0)),
            listOf(listOf(0.0, 5.0), listOf(8.0, 13.0)),
            listOf(listOf(0.0, 8.0), listOf(7.0, 13.0)),
            listOf(listOf(7.0, 13.0), listOf(0.0, 5.0)),
            listOf(listOf(-1.0, 13.0)),
            listOf(listOf(0.0, 14.0)),
            listOf(listOf(0.1, 13.0)),
            listOf(listOf(0.0, 13.0, 1.0)),
        )
        invalid.forEach { paragraphs ->
            val document = ReadAlongDocument.from(response(text = "First\n\nSecond", paragraphs = paragraphs, cues = emptyList()))
            assertEquals(listOf("First", "Second"), document.paragraphs.map(document::textIn), paragraphs.toString())
        }
    }

    @Test
    fun `consecutive pronunciation expansion spans seek to their earliest cue`() {
        val document = ReadAlongDocument.from(response(cues = listOf(
            listOf(0.0, 3.0, 0.1),
            listOf(0.0, 3.0, 0.2),
            listOf(0.0, 3.0, 0.3),
            listOf(4.0, 10.0, 0.4),
        )))

        assertTrue(document.hasReliableTimings)
        assertEquals(2, document.highlightAtMillis(300).cueIndex)
        assertEquals(0.1, document.seekSecondsForOffset(2))
        assertEquals(100L, document.seekMillisForOffset(2))
        assertNull(document.seekMillisForOffset(3))
    }

    @Test
    fun `partial overlaps backwards spans and backwards time remain malformed`() {
        listOf(
            listOf(listOf(0.0, 3.0, 0.0), listOf(2.0, 4.0, 0.1)),
            listOf(listOf(0.0, 3.0, 0.0), listOf(0.0, 4.0, 0.1)),
            listOf(listOf(4.0, 10.0, 0.0), listOf(0.0, 3.0, 0.1)),
            listOf(listOf(0.0, 3.0, 0.2), listOf(4.0, 10.0, 0.1)),
        ).forEach { cues ->
            assertEquals(ReadAlongTimingState.Malformed, ReadAlongDocument.from(response(cues = cues)).timingState)
        }
    }

    @Test
    fun `seek uses the first representable millisecond at or after a cue`() {
        listOf(0.0, 0.0001, 0.1001, 1.001, 1.0000000000000002, 123.4567, Long.MAX_VALUE / 1000.0).forEach { seconds ->
            val document = ReadAlongDocument.from(response(cues = listOf(listOf(0.0, 3.0, seconds))))
            val millis = requireNotNull(document.seekMillisForOffset(1))
            assertTrue(millis / 1000.0 >= seconds, "$seconds -> $millis")
            assertTrue(millis == 0L || (millis - 1) / 1000.0 < seconds, "$seconds -> $millis")
            assertEquals(0, document.highlightAtMillis(millis).cueIndex)
        }
        listOf(-0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE).forEach { seconds ->
            assertEquals(
                ReadAlongTimingState.Malformed,
                ReadAlongDocument.from(response(cues = listOf(listOf(0.0, 3.0, seconds)))).timingState,
            )
        }
    }

    @Test
    fun `binary seek lookup remains correct with ten thousand cues`() {
        val text = "x".repeat(50_000)
        val cues = (0 until 10_000).map { index ->
            listOf((index * 5).toDouble(), (index * 5 + 4).toDouble(), index / 4.0)
        }
        val document = ReadAlongDocument.from(
            response(text = text, paragraphs = listOf(listOf(0.0, 50_000.0)), cues = cues),
        )

        assertEquals(1_250.0, document.seekSecondsForOffset(25_001))
        assertEquals(9_999, document.highlightAtMillis(2_499_999).cueIndex)
    }
}

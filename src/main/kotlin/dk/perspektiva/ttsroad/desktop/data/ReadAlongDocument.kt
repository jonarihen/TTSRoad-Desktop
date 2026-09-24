package dk.perspektiva.ttsroad.desktop.data

import kotlin.math.abs
import kotlin.math.ceil

/** A half-open `[start, end)` character range into a chapter's narration text. */
data class TextSpan(val start: Int, val end: Int) {
    val length: Int get() = end - start
    fun contains(offset: Int): Boolean = offset in start until end
    fun overlaps(other: TextSpan): Boolean = start < other.end && other.start < end
}

/** One timed word-like unit and the media position where it begins. */
data class ReadAlongCue(val span: TextSpan, val startSeconds: Double)

data class ReadAlongHighlight(
    val cueIndex: Int = -1,
    val word: TextSpan? = null,
    val sentenceIndex: Int = -1,
    val sentence: TextSpan? = null,
) {
    val isActive: Boolean get() = cueIndex >= 0

    companion object {
        val None: ReadAlongHighlight = ReadAlongHighlight()
    }
}

/** Why a loaded document can or cannot follow playback confidently. */
enum class ReadAlongTimingState {
    Timed,
    TextOnly,
    Malformed,
}

/**
 * Validated chapter text with binary cue, sentence, paragraph, and seek lookup.
 *
 * Every timing lookup consumes the media position reported by the player. Playback speed therefore
 * needs no scaling and cannot accumulate wall-clock drift.
 */
data class ReadAlongDocument(
    val chapterId: Int = 0,
    val fictionId: Int = 0,
    val title: String = "",
    val chapterNumber: Double? = null,
    val audioDurationSeconds: Double = 0.0,
    val text: String = "",
    val paragraphs: List<TextSpan> = emptyList(),
    val cues: List<ReadAlongCue> = emptyList(),
    val timingState: ReadAlongTimingState = ReadAlongTimingState.TextOnly,
) {
    val sentences: List<TextSpan> = segmentReadAlongSentences(text, paragraphs)
    val hasReliableTimings: Boolean get() = timingState == ReadAlongTimingState.Timed && cues.isNotEmpty()

    fun textIn(span: TextSpan): String =
        text.substring(span.start.coerceIn(0, text.length), span.end.coerceIn(0, text.length))

    fun cueIndexAt(positionSeconds: Double): Int {
        if (!hasReliableTimings || !positionSeconds.isFinite()) return -1
        var low = 0
        var high = cues.lastIndex
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (cues[middle].startSeconds <= positionSeconds) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }

    fun highlightAtMillis(positionMs: Long): ReadAlongHighlight {
        val cueIndex = cueIndexAt(positionMs / 1000.0)
        if (cueIndex < 0) return ReadAlongHighlight.None
        val word = cues[cueIndex].span
        val sentenceIndex = spanIndexAt(sentences, word.start)
        return ReadAlongHighlight(cueIndex, word, sentenceIndex, sentences.getOrNull(sentenceIndex))
    }

    fun paragraphIndexAt(offset: Int): Int = spanIndexAt(paragraphs, offset)

    /** A click on untimed text deliberately returns null rather than borrowing a nearby cue. */
    fun seekSecondsForOffset(offset: Int): Double? {
        if (!hasReliableTimings) return null
        var low = 0
        var high = cues.lastIndex
        var before = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (cues[middle].span.start <= offset) {
                before = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        if (before < 0 || !cues[before].span.contains(offset)) return null
        val start = cues[before].span.start
        low = 0
        high = before
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cues[middle].span.start < start) low = middle + 1 else high = middle
        }
        return cues[low].startSeconds
    }

    fun seekMillisForOffset(offset: Int): Long? {
        val seconds = seekSecondsForOffset(offset) ?: return null
        if (!seconds.isSeekableTime()) return null
        var millis = ceil(seconds * 1000.0).toLong()
        if (millis < Long.MAX_VALUE && millis / 1000.0 < seconds) millis++
        if (millis > 0L && (millis - 1) / 1000.0 >= seconds) millis--
        if (millis / 1000.0 >= seconds && (millis == 0L || (millis - 1) / 1000.0 < seconds)) {
            return millis
        }
        var low = 0L
        var high = Long.MAX_VALUE
        while (low < high) {
            val middle = low + (high - low) / 2
            if (middle / 1000.0 < seconds) low = middle + 1 else high = middle
        }
        return low
    }

    private fun spanIndexAt(spans: List<TextSpan>, offset: Int): Int {
        var low = 0
        var high = spans.lastIndex
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (spans[middle].start <= offset) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }

    companion object {
        fun from(response: ReadAlongResponse): ReadAlongDocument {
            val text = response.text
            val boundaries = codePointBoundaries(text)
            val paragraphs = validatedParagraphs(text, response.paragraphs, boundaries)
                ?: paragraphsFromLineBreaks(text)

            val parsed = response.cues.mapNotNull { row ->
                if (row.size != 3 || !row[2].isSeekableTime()) return@mapNotNull null
                row.toSpan(boundaries)?.let { ReadAlongCue(it, row[2]) }
            }

            val monotonic = parsed.zipWithNext().all { (left, right) ->
                right.startSeconds >= left.startSeconds &&
                    (right.span.start >= left.span.end || right.span == left.span)
            }
            val timingState = when {
                !response.chapter.hasTimings && response.cues.isEmpty() -> ReadAlongTimingState.TextOnly
                parsed.isEmpty() || !monotonic || parsed.size != response.cues.size -> ReadAlongTimingState.Malformed
                else -> ReadAlongTimingState.Timed
            }

            return ReadAlongDocument(
                chapterId = response.chapter.id,
                fictionId = response.chapter.fictionId,
                title = response.chapter.title,
                chapterNumber = response.chapter.chapterNumber,
                audioDurationSeconds = response.chapter.audioDuration
                    ?.takeIf(Double::isFinite)
                    ?.coerceAtLeast(0.0)
                    ?: 0.0,
                text = text,
                paragraphs = paragraphs,
                cues = if (timingState == ReadAlongTimingState.Timed) parsed else emptyList(),
                timingState = timingState,
            )
        }

        private fun Double.isSeekableTime(): Boolean =
            isFinite() && this >= 0.0 && this <= Long.MAX_VALUE / 1000.0

        private fun codePointBoundaries(text: String): IntArray {
            val boundaries = IntArray(text.codePointCount(0, text.length) + 1)
            var utf16Offset = 0
            var codePointOffset = 0
            while (utf16Offset < text.length) {
                utf16Offset += Character.charCount(text.codePointAt(utf16Offset))
                boundaries[++codePointOffset] = utf16Offset
            }
            return boundaries
        }

        private fun List<Double>.toSpan(boundaries: IntArray): TextSpan? {
            if (size < 2) return null
            val start = this[0].toOffset(boundaries.lastIndex) ?: return null
            val end = this[1].toOffset(boundaries.lastIndex) ?: return null
            return if (end > start) TextSpan(boundaries[start], boundaries[end]) else null
        }

        private fun Double.toOffset(maxOffset: Int): Int? {
            if (!isFinite() || this < 0.0 || this > maxOffset.toDouble()) return null
            val offset = toInt()
            return offset.takeIf { it.toDouble() == this }
        }

        private fun validatedParagraphs(
            text: String,
            rows: List<List<Double>>,
            boundaries: IntArray,
        ): List<TextSpan>? {
            if (rows.isEmpty()) return null
            val paragraphs = ArrayList<TextSpan>(rows.size)
            var previousEnd = 0
            for (row in rows) {
                if (row.size != 2) return null
                val span = row.toSpan(boundaries) ?: return null
                if (span.start < previousEnd) return null
                while (previousEnd < span.start) {
                    if (!text[previousEnd++].isWhitespace()) return null
                }
                paragraphs += span
                previousEnd = span.end
            }
            while (previousEnd < text.length) {
                if (!text[previousEnd++].isWhitespace()) return null
            }
            return paragraphs
        }

        private fun paragraphsFromLineBreaks(text: String): List<TextSpan> {
            val result = ArrayList<TextSpan>()
            var cursor = 0
            while (cursor < text.length) {
                while (cursor < text.length && text[cursor] == '\n') cursor++
                val start = cursor
                while (cursor < text.length && text[cursor] != '\n') cursor++
                var end = cursor
                while (end > start && text[end - 1].isWhitespace()) end--
                if (end > start) result += TextSpan(start, end)
            }
            return result
        }
    }
}

/** Duration mismatches disable confident highlighting against stale timing text/audio. */
fun readAlongTimingsMatch(documentDurationSeconds: Double, playerDurationMs: Long): Boolean {
    if (documentDurationSeconds <= 0.0 || playerDurationMs <= 0L) return true
    val playerSeconds = playerDurationMs / 1000.0
    val tolerance = maxOf(5.0, documentDurationSeconds * 0.02)
    return abs(documentDurationSeconds - playerSeconds) <= tolerance
}

/** All case-insensitive, non-overlapping occurrences used by find-in-chapter. */
fun readAlongMatches(text: String, query: String): List<TextSpan> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    val result = ArrayList<TextSpan>()
    var cursor = 0
    while (cursor <= text.length - needle.length) {
        val found = text.indexOf(needle, cursor, ignoreCase = true)
        if (found < 0) break
        result += TextSpan(found, found + needle.length)
        cursor = found + needle.length.coerceAtLeast(1)
    }
    return result
}

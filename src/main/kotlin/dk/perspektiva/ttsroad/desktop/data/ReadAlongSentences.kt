package dk.perspektiva.ttsroad.desktop.data

/** Sentence spans for a stable highlight band; a sentence never crosses a paragraph. */
fun segmentReadAlongSentences(text: String, paragraphs: List<TextSpan>): List<TextSpan> {
    if (text.isEmpty()) return emptyList()
    val result = ArrayList<TextSpan>()
    paragraphs.forEach { paragraph ->
        val start = paragraph.start.coerceIn(0, text.length)
        val end = paragraph.end.coerceIn(0, text.length)
        if (end > start) segmentParagraph(text, start, end, result)
    }
    return result
}

private fun segmentParagraph(text: String, start: Int, end: Int, output: MutableList<TextSpan>) {
    var sentenceStart = start
    var cursor = start
    while (cursor < end) {
        if (text[cursor] !in Terminators) {
            cursor++
            continue
        }
        val breakEnd = sentenceBreakEnd(text, cursor, end)
        if (breakEnd < 0) {
            cursor++
            continue
        }
        output.addTrimmed(text, sentenceStart, breakEnd)
        cursor = breakEnd
        while (cursor < end && text[cursor].isWhitespace()) cursor++
        sentenceStart = cursor
    }
    output.addTrimmed(text, sentenceStart, end)
}

private fun sentenceBreakEnd(text: String, at: Int, limit: Int): Int {
    var end = at + 1
    while (end < limit && text[end] in Terminators) end++
    val runLength = end - at
    while (end < limit && text[end] in Closers) end++
    if (end >= limit) return end
    if (!text[end].isWhitespace()) return -1
    if (runLength == 1 && text[at] == '.' && endsWithAbbreviation(text, at)) return -1
    var next = end
    while (next < limit && text[next].isWhitespace()) next++
    if (next < limit && !startsSentence(text[next])) return -1
    return end
}

private fun endsWithAbbreviation(text: String, dot: Int): Boolean {
    var start = dot
    while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '.')) start--
    val token = text.substring(start, dot)
    return (token.length == 1 && token.firstOrNull()?.isLetter() == true) || token.lowercase() in Abbreviations
}

private fun startsSentence(char: Char): Boolean = char.isUpperCase() || char.isDigit() || char in Openers

private fun MutableList<TextSpan>.addTrimmed(text: String, from: Int, to: Int) {
    var start = from
    var end = to
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end - 1].isWhitespace()) end--
    if (end > start) add(TextSpan(start, end))
}

private val Terminators = charArrayOf('.', '!', '?', '…')
private val Closers = charArrayOf('"', '\'', '”', '’', ')', ']', '»')
private val Openers = charArrayOf('"', '\'', '“', '‘', '(', '[', '«', '—', '–', '-', '*')
private val Abbreviations = setOf(
    "mr", "mrs", "ms", "mx", "dr", "prof", "st", "sr", "jr", "vs",
    "capt", "lt", "sgt", "col", "gen", "rev", "hon", "e.g", "i.e",
)

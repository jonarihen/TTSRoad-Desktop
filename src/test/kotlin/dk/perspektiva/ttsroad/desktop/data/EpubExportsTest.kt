package dk.perspektiva.ttsroad.desktop.data

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class EpubExportsTest {

    @Test
    fun `suggested filename sanitizes unsafe characters and ensures epub extension`() {
        assertEquals("The_Wandering_Inn.epub", suggestedEpubFileName("The: Wandering? Inn*"))
        assertEquals("book.epub", suggestedEpubFileName("../../book.epub"))
        assertEquals("book.epub", suggestedEpubFileName("..\\..\\book"))
        assertEquals("fiction.epub", suggestedEpubFileName("   "))
        assertEquals("story.epub", suggestedEpubFileName("story.EPUB"))
    }
}

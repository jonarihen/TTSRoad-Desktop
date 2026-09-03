package dk.perspektiva.ttsroad.desktop.data

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The browse settings file.
 *
 * The interesting cases are all about a file written by a *different* build: the on-disk type is
 * fully nullable and the sort is a string, so an order this build has never heard of degrades to
 * the default rather than taking the shelf's whole arrangement down with it.
 */
class BrowsePreferencesTest {

    @TempDir
    lateinit var tempDir: File

    private fun file() = tempDir.resolve("browse.json")

    @Test
    fun `an order survives a restart`() {
        val file = file()
        FileBrowsePreferencesStore(file).update {
            it.copy(sort = FictionSort.Title, tags = setOf("LitRPG"), browsingAll = true)
        }

        val reopened = FileBrowsePreferencesStore(file).preferences.value

        assertEquals(FictionSort.Title, reopened.sort)
        assertEquals(setOf("LitRPG"), reopened.tags)
        assertTrue(reopened.browsingAll)
    }

    @Test
    fun `an order this build has never heard of falls back to the default`() {
        val file = file()
        file.writeText("""{"sort":"ByVibes","tags":["LitRPG"],"browsingAll":true}""")

        val loaded = FileBrowsePreferencesStore(file).preferences.value

        // The unknown key costs its own field and nothing else.
        assertEquals(FictionSort.Default, loaded.sort)
        assertEquals(setOf("LitRPG"), loaded.tags)
        assertTrue(loaded.browsingAll)
    }

    @Test
    fun `a truncated or unreadable file opens on the defaults`() {
        val file = file()
        file.writeText("""{"sort":"Titl""")

        assertEquals(BrowsePreferences(), FileBrowsePreferencesStore(file).preferences.value)
    }

    @Test
    fun `a file with no keys at all is every default`() {
        val file = file()
        file.writeText("{}")

        val loaded = FileBrowsePreferencesStore(file).preferences.value

        assertEquals(FictionSort.Default, loaded.sort)
        assertTrue(loaded.tags.isEmpty())
        assertTrue(!loaded.browsingAll)
    }

    @Test
    fun `blank tags are dropped and the remembered set is bounded`() {
        val store = InMemoryBrowsePreferencesStore()

        store.update { it.copy(tags = setOf("  ", "LitRPG", "\t")) }
        assertEquals(setOf("LitRPG"), store.preferences.value.tags)

        store.update { current ->
            current.copy(tags = (1..BrowsePreferences.MaxRememberedTags + 10).map { "tag $it" }.toSet())
        }
        assertEquals(BrowsePreferences.MaxRememberedTags, store.preferences.value.tags.size)
    }

    @Test
    fun `the same tag in two spellings is remembered once`() {
        val store = InMemoryBrowsePreferencesStore()

        store.update { it.copy(tags = setOf("LitRPG", "litrpg")) }

        assertEquals(1, store.preferences.value.tags.size)
    }
}

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
            it.copy(sort = FictionSort.Title, tags = setOf("LitRPG"), browsingAll = true, sources = setOf("ao3", UnknownSourceKey))
        }

        val reopened = FileBrowsePreferencesStore(file).preferences.value

        assertEquals(FictionSort.Title, reopened.sort)
        assertEquals(setOf("LitRPG"), reopened.tags)
        assertEquals(setOf("ao3", UnknownSourceKey), reopened.sources)
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
    fun `recently updated migrates to new chapters and subsequent writes use the new name`() {
        val file = file()
        file.writeText("""{"sort":"RecentlyUpdated","tags":["LitRPG"],"browsingAll":true}""")
        val store = FileBrowsePreferencesStore(file)

        assertEquals(FictionSort.NewChapters, store.preferences.value.sort)
        assertEquals(setOf("LitRPG"), store.preferences.value.tags)
        assertTrue(store.preferences.value.browsingAll)
        assertTrue(store.preferences.value.sources.isEmpty())
        store.update { it.copy(sources = setOf("ao3")) }
        assertTrue(file.readText().contains("\"sort\":\"NewChapters\""))
        assertTrue(!file.readText().contains("RecentlyUpdated"))
        assertEquals(store.preferences.value, FileBrowsePreferencesStore(file).preferences.value)
    }

    @Test
    fun `new and existing sort names round trip with the desktop default preserved`() {
        assertEquals(FictionSort.NewChapters, FictionSort.Default)
        for (sort in FictionSort.entries) {
            assertEquals(sort, FictionSort.fromStorage(sort.name))
            val file = file()
            file.writeText("""{"sort":"${sort.name}"}""")
            assertEquals(sort, FileBrowsePreferencesStore(file).preferences.value.sort)
        }
        assertEquals(FictionSort.Default, FictionSort.fromStorage(null))
        assertEquals(FictionSort.Default, FictionSort.fromStorage("future order"))
    }

    @Test
    fun `null fields and future fields do not lose source keys`() {
        val file = file()
        file.writeText("""{"sort":null,"tags":null,"browsingAll":null,"sources":["ao3","__unknown__"],"future":true}""")
        assertEquals(
            BrowsePreferences(sources = setOf("ao3", UnknownSourceKey)),
            FileBrowsePreferencesStore(file).preferences.value,
        )
        file.writeText("""{"sort":"RecentlyListened","sources":null}""")
        assertEquals(
            BrowsePreferences(sort = FictionSort.RecentlyListened),
            FileBrowsePreferencesStore(file).preferences.value,
        )
    }

    @Test
    fun `source keys are trimmed bounded and never case folded or replaced by labels`() {
        val file = file()
        val store = FileBrowsePreferencesStore(file)
        store.update { it.copy(sources = setOf(" ao3 ", "ao3", "AO3", "", " \t", UnknownSourceKey)) }
        assertEquals(setOf("ao3", "AO3", UnknownSourceKey), store.preferences.value.sources)
        assertEquals(store.preferences.value, FileBrowsePreferencesStore(file).preferences.value)
        assertTrue(!file.readText().contains("Archive of Our Own"))
        store.update { it.copy(sources = (1..BrowsePreferences.MaxRememberedSources + 10).map { "adapter$it" }.toSet()) }
        assertEquals(BrowsePreferences.MaxRememberedSources, store.preferences.value.sources.size)
        store.update { it.copy(sources = emptySet()) }
        assertTrue(FileBrowsePreferencesStore(file).preferences.value.sources.isEmpty())
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
        assertTrue(loaded.sources.isEmpty())
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

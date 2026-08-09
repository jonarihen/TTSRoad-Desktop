package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.FakeRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPreferencesTest {
    @Test
    fun `preference coercion honours the server vocabulary and ranges`() {
        val merged = ReaderPreferencesWire(
            fontSize = 99.0,
            lineHeight = Double.NaN,
            theme = "sepia",
            highlight = "future-mode",
        ).mergeInto(ReaderPreferences(theme = ReaderTheme.Light, highlight = ReaderHighlight.Word))

        assertEquals(30.0, merged.fontSize)
        assertEquals(ReaderPreferences.DefaultLineHeight, merged.lineHeight)
        assertEquals(ReaderTheme.Sepia, merged.theme)
        assertEquals(ReaderHighlight.Word, merged.highlight)
    }

    @Test
    fun `server refresh merges supported keys over the local fallback`(@TempDir root: java.io.File) = runTest {
        val repository = FakeRepository(
            readerPreferencesResult = Result.success(
                ReaderPreferencesResponse(ReaderPreferencesWire(fontSize = 24.0, theme = "light")),
            ),
        )
        val store = FileReaderPreferencesStore(
            repository,
            root.resolve("reader.json"),
            StandardTestDispatcher(testScheduler),
        )

        store.refreshFromServer()

        assertEquals(24.0, store.preferences.value.fontSize)
        assertEquals(ReaderTheme.Light, store.preferences.value.theme)
        assertEquals(ReaderPreferences.DefaultLineHeight, store.preferences.value.lineHeight)
        store.close()
    }

    @Test
    fun `a local change persists immediately and patches only reader keys`(@TempDir root: java.io.File) = runTest {
        val repository = FakeRepository(
            readerPreferencesResult = Result.success(ReaderPreferencesResponse()),
        )
        val file = root.resolve("reader.json")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileReaderPreferencesStore(repository, file, dispatcher)

        store.update { it.copy(fontSize = 25.0, theme = ReaderTheme.Sepia) }
        val reloaded = FileReaderPreferencesStore(repository, file, dispatcher)
        assertEquals(25.0, reloaded.preferences.value.fontSize)
        reloaded.close()
        advanceTimeBy(301)
        advanceUntilIdle()

        val patch = repository.readerPreferencePatches.single()
        assertEquals(25.0, patch.fontSize)
        assertEquals("sepia", patch.theme)
        store.close()
    }

    @Test
    fun `offline or old-server refresh leaves the local fallback untouched`() = runTest {
        val repository = FakeRepository(readerPreferencesResult = Result.success(null))
        val store = InMemoryReaderPreferencesStore(ReaderPreferences(fontSize = 23.0))

        store.refreshFromServer()

        assertEquals(23.0, store.preferences.value.fontSize)
    }
}

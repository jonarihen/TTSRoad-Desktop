package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.CoverUploadResult
import dk.perspektiva.ttsroad.desktop.data.FictionMetadataFields
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.LibraryCache
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The editor's whole job is deciding *what not to send*.
 *
 * On a server that tracks hand-edited metadata a write claims the field permanently, so a request
 * carrying a value nobody typed freezes it against every future refresh of the source. Most of what
 * is asserted here is therefore an absence: which keys are missing from the patch, and that
 * releasing a field sends no value alongside the release.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FictionMetadataStateHolderTest {

    private val serial = FictionSummary(
        id = 7,
        title = "Wandering Inn",
        author = "pirateaba",
        description = "An inn.",
        tags = listOf("LitRPG", "Fantasy"),
        voice = "en-US-AriaNeural",
    )

    @Test
    fun `only the fields somebody actually changed are sent`() = runTest {
        val fixture = fixture()
        fixture.repository.updateFictionResult = Result.success(serial.copy(title = "The Wandering Inn"))
        fixture.holder.load(serial)

        fixture.holder.setAuthor("pirateaba ")

        // Author differs only by trailing space, which is not a change once trimmed.
        assertFalse(fixture.holder.state.value.hasChanges)

        fixture.holder.setTitle("The Wandering Inn")
        fixture.holder.save()
        runCurrent()

        val (fictionId, request) = fixture.repository.updatedFictions.single()
        assertEquals(7, fictionId)
        assertEquals("The Wandering Inn", request.title)
        assertNull(request.author, "an untouched author must not be claimed")
        assertNull(request.description, "an untouched description must not be claimed")
        assertNull(request.tags, "untouched tags must not be claimed")
        assertNull(request.voice, "an untouched voice must not be sent")
        fixture.close()
    }

    @Test
    fun `changing only the voice claims no metadata field`() = runTest {
        val fixture = fixture()
        fixture.repository.updateFictionResult = Result.success(serial.copy(voice = "en-GB-RyanNeural"))
        fixture.holder.load(serial)

        fixture.holder.setVoice("en-GB-RyanNeural")

        // Narration voice is a conversion setting, not metadata — it is not an override.
        assertTrue(fixture.holder.state.value.voiceChanged)
        assertTrue(fixture.holder.state.value.changedFields.isEmpty())

        fixture.holder.save()
        runCurrent()

        val request = fixture.repository.updatedFictions.single().second
        assertEquals("en-GB-RyanNeural", request.voice)
        assertNull(request.title)
        assertNull(request.tags)
        fixture.close()
    }

    @Test
    fun `a tag typed but never committed is still saved`() = runTest {
        val fixture = fixture()
        fixture.repository.updateFictionResult = Result.success(
            serial.copy(tags = listOf("LitRPG", "Fantasy", "Progression")),
        )
        fixture.holder.load(serial)

        // Pressing Save with a half-typed tag left in the field must not silently drop it.
        fixture.holder.setTagDraft("Progression")
        fixture.holder.save()
        runCurrent()

        val request = fixture.repository.updatedFictions.single().second
        assertContentEquals(listOf("LitRPG", "Fantasy", "Progression"), request.tags)
        assertEquals("", fixture.holder.state.value.draft.tagDraft)
        fixture.close()
    }

    @Test
    fun `a duplicate tag is folded rather than added twice`() = runTest {
        val fixture = fixture()
        fixture.holder.load(serial)

        fixture.holder.setTagDraft("litrpg")
        fixture.holder.commitTagDraft()

        assertContentEquals(listOf("LitRPG", "Fantasy"), fixture.holder.state.value.draft.tags)
        assertFalse(fixture.holder.state.value.hasChanges, "folding a duplicate changes nothing")
        fixture.close()
    }

    @Test
    fun `an empty title or voice is refused before any request`() = runTest {
        val fixture = fixture()
        fixture.holder.load(serial)

        fixture.holder.setTitle("   ")
        fixture.holder.save()
        runCurrent()

        assertEquals("Title cannot be empty", fixture.holder.state.value.error)
        assertTrue(fixture.repository.updatedFictions.isEmpty())

        fixture.holder.setTitle("Wandering Inn")
        fixture.holder.setVoice("")
        fixture.holder.save()
        runCurrent()

        assertEquals("Voice cannot be empty", fixture.holder.state.value.error)
        assertTrue(fixture.repository.updatedFictions.isEmpty())
        fixture.close()
    }

    @Test
    fun `releasing a field sends clear_overrides and no values`() = runTest {
        val owned = serial.copy(metadataOverrides = listOf("title", "tags"))
        val fixture = fixture()
        fixture.repository.updateFictionResult = Result.success(owned.copy(metadataOverrides = emptyList()))
        fixture.holder.load(owned)

        // Something is typed but unsaved: releasing must not carry it along as a fresh claim.
        fixture.holder.setTitle("Something Else")
        fixture.holder.useSourceValues(setOf(FictionMetadataFields.Title))
        runCurrent()

        val request = fixture.repository.updatedFictions.single().second
        assertEquals(listOf("title"), request.clearOverrides)
        assertNull(request.title, "releasing a field must not re-claim it in the same request")
        assertNull(request.tags)
        assertEquals(
            "Something Else",
            fixture.holder.state.value.draft.title,
            "clearing removes protection; it does not restore what the source said",
        )
        fixture.close()
    }

    @Test
    fun `releasing a field the account does not own does nothing`() = runTest {
        val fixture = fixture()
        fixture.holder.load(serial)

        fixture.holder.useSourceValues(setOf(FictionMetadataFields.Description))
        runCurrent()

        assertTrue(fixture.repository.updatedFictions.isEmpty())
        fixture.close()
    }

    @Test
    fun `an older server that drops description and tags is reported rather than called a save`() = runTest {
        val fixture = fixture()
        // A server predating hand-edited metadata echoes the title it stored and ignores the rest.
        fixture.repository.updateFictionResult = Result.success(
            serial.copy(title = "The Wandering Inn"),
        )
        fixture.holder.load(serial)

        fixture.holder.setTitle("The Wandering Inn")
        fixture.holder.setDescription("A very good inn.")
        fixture.holder.save()
        runCurrent()

        val error = fixture.holder.state.value.error.orEmpty()
        assertTrue(error.contains("Description"), error)
        assertTrue(error.contains("older than hand-edited metadata"), error)
        assertNull(fixture.holder.state.value.notice, "a partial write is not a save")
        fixture.close()
    }

    @Test
    fun `a metadata save survives a cover the server refuses`() = runTest {
        val cover = imageFile("cover.jpg", bytes = 8)
        val fixture = fixture(picker = { cover })
        fixture.repository.updateFictionResult = Result.success(serial.copy(title = "The Wandering Inn"))
        fixture.repository.uploadCoverResult =
            Result.success(CoverUploadResult.Rejected("That image is too large"))
        fixture.holder.load(serial)

        fixture.holder.setTitle("The Wandering Inn")
        fixture.holder.chooseCover()
        fixture.holder.save()
        runCurrent()

        assertEquals("The Wandering Inn", fixture.holder.state.value.fiction?.title)
        assertEquals("That image is too large", fixture.holder.state.value.error)
        assertEquals(
            cover,
            fixture.holder.state.value.chosenCover,
            "a refusal a person can act on keeps the file for a retry",
        )
        fixture.close()
    }

    @Test
    fun `a server without the cover route stops offering the control`() = runTest {
        val cover = imageFile("cover.png", bytes = 8)
        val fixture = fixture(picker = { cover })
        fixture.repository.updateFictionResult = Result.success(serial)
        fixture.repository.uploadCoverResult = Result.success(CoverUploadResult.Unsupported)
        fixture.holder.load(serial)

        fixture.holder.chooseCover()
        fixture.holder.save()
        runCurrent()

        assertFalse(fixture.holder.state.value.coverUploadSupported)
        assertNull(
            fixture.holder.state.value.chosenCover,
            "there is nothing to retry against a server without the route",
        )
        fixture.close()
    }

    @Test
    fun `the wrong kind of image is refused before it is uploaded`() = runTest {
        val fixture = fixture(picker = { imageFile("scan.tiff", bytes = 8) })
        fixture.holder.load(serial)

        fixture.holder.chooseCover()

        assertEquals(
            "Cover art has to be a JPEG, PNG, WEBP or GIF image",
            fixture.holder.state.value.error,
        )
        assertNull(fixture.holder.state.value.chosenCover)
        assertTrue(fixture.repository.uploadedCovers.isEmpty())
        fixture.close()
    }

    @Test
    fun `an image over the server's published ceiling is refused with both numbers`() = runTest {
        val fixture = fixture(picker = { imageFile("huge.jpg", bytes = 3 * 1024 * 1024) })
        fixture.holder.load(serial, maxCoverBytes = 1024L * 1024)

        fixture.holder.chooseCover()

        val error = fixture.holder.state.value.error.orEmpty()
        assertTrue(error.contains("3.0 MB"), error)
        assertTrue(error.contains("1.0 MB"), error)
        fixture.close()
    }

    @Test
    fun `a fresher copy of the same fiction keeps what is being typed`() = runTest {
        val fixture = fixture()
        fixture.holder.load(serial)
        fixture.holder.setTitle("Half-typed tit")

        // The screen re-loads whenever the library cache publishes a fresher row.
        fixture.holder.load(serial.copy(doneChapters = 12))

        assertEquals("Half-typed tit", fixture.holder.state.value.draft.title)
        assertEquals(12, fixture.holder.state.value.fiction?.doneChapters)

        // A different fiction is a different form.
        fixture.holder.load(serial.copy(id = 9, title = "Other"))
        assertEquals("Other", fixture.holder.state.value.draft.title)
        fixture.close()
    }

    @Test
    fun `revert drops every unsaved edit including a chosen cover`() = runTest {
        val fixture = fixture(picker = { imageFile("cover.jpg", bytes = 8) })
        fixture.holder.load(serial)

        fixture.holder.setTitle("Something else")
        fixture.holder.chooseCover()
        fixture.holder.revertEdits()

        assertEquals("Wandering Inn", fixture.holder.state.value.draft.title)
        assertNull(fixture.holder.state.value.chosenCover)
        assertFalse(fixture.holder.state.value.hasChanges)
        fixture.close()
    }

    @Test
    fun `saving nothing makes no request`() = runTest {
        val fixture = fixture()
        fixture.holder.load(serial)

        fixture.holder.save()
        runCurrent()

        assertEquals("Nothing to save", fixture.holder.state.value.notice)
        assertTrue(fixture.repository.updatedFictions.isEmpty())
        fixture.close()
    }

    @Test
    fun `a failed save keeps the draft so the edit is not lost`() = runTest {
        val fixture = fixture()
        fixture.repository.updateFictionResult = Result.failure(java.io.IOException("offline"))
        fixture.holder.load(serial)

        fixture.holder.setTitle("The Wandering Inn")
        fixture.holder.save()
        runCurrent()

        assertEquals("The Wandering Inn", fixture.holder.state.value.draft.title)
        assertFalse(fixture.holder.state.value.isBusy)
        assertTrue(fixture.holder.state.value.error.orEmpty().isNotEmpty())
        fixture.close()
    }

    @Test
    fun `a session ending empties the form`() = runTest {
        val fixture = fixture()
        fixture.holder.load(serial)
        fixture.holder.setTitle("The Wandering Inn")

        fixture.holder.sessionEnded()

        assertNull(fixture.holder.state.value.fiction)
        assertEquals("", fixture.holder.state.value.draft.title)
        fixture.close()
    }

    // --- fixture ----------------------------------------------------------------------------------

    private class Fixture(
        val repository: FakeRepository,
        val cache: LibraryCache,
        val holder: FictionMetadataStateHolder,
    ) {
        fun close() {
            holder.clear()
            cache.close()
        }
    }

    private fun TestScope.fixture(picker: CoverImagePicker = CoverImagePicker { null }): Fixture {
        val repository = FakeRepository()
        val cache = LibraryCache(repository, UnconfinedTestDispatcher(testScheduler))
        return Fixture(
            repository,
            cache,
            FictionMetadataStateHolder(
                repository,
                cache,
                picker = picker,
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            ),
        )
    }

    private fun imageFile(name: String, bytes: Int): File =
        java.nio.file.Files.createTempDirectory("ttsroad-cover").resolve(name).toFile()
            .also { it.writeBytes(ByteArray(bytes)) }
}

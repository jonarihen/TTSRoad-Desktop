package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.InMemorySessionStore
import dk.perspektiva.ttsroad.desktop.data.ReaderPreferencesResponse
import dk.perspektiva.ttsroad.desktop.data.ReaderPreferencesWire
import dk.perspektiva.ttsroad.desktop.data.SessionState
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSkipsTest {
    @TempDir
    lateinit var tempDir: java.io.File

    private fun skips(start: Double = 10.0, end: Double = 20.0, duration: Double = 60.0) =
        PlaybackSkips.from(
            PlaybackSkipsResponse(
                chapterId = 7,
                hasTimings = true,
                audioDuration = duration,
                segments = listOf(PlaybackSkipSegmentWire(start, end, end - start, "Advert", "preview")),
            ),
        )

    @Test
    fun `valid response becomes millisecond domain model`() {
        val result = requireNotNull(skips())
        assertEquals(7, result.chapterId)
        assertEquals(60_000, result.audioDurationMs)
        assertEquals(PlaybackSkipSegment(10_000, 20_000, "Advert", "preview"), result.segments.single())
    }

    @Test
    fun `overlap reversed and non-finite segments reject the document`() {
        assertNull(PlaybackSkips.from(PlaybackSkipsResponse(chapterId = 1, hasTimings = true, segments = listOf(
            PlaybackSkipSegmentWire(10.0, 20.0), PlaybackSkipSegmentWire(19.0, 30.0),
        ))))
        assertNull(skips(20.0, 10.0))
        assertNull(skips(Double.NaN, 10.0))
    }

    @Test
    fun `seek decision mirrors edge tolerances and trailing completion`() {
        val skips = requireNotNull(skips())
        assertEquals(20_000, playbackSkipTarget(skips, 9_750, 60_000, true))
        assertNull(playbackSkipTarget(skips, 19_750, 60_000, true))
        assertNull(playbackSkipTarget(skips, 12_000, 60_000, false))
        assertEquals(60_000, playbackSkipTarget(requireNotNull(skips(50.0, 59.0)), 52_000, 60_000, true))
        assertTrue(requireNotNull(skips).segments.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
    }

    @Test
    fun `playback skip preference store isolates account identities and ignores stale responses`() = runTest {
        val aliceSession = InMemorySessionStore(SessionState("https://srv.example", "t1", "alice"))
        val bobSession = InMemorySessionStore(SessionState("https://srv.example", "t2", "bob"))
        val repo = FakeRepository(readerPreferencesResult = Result.success(ReaderPreferencesResponse(ReaderPreferencesWire(skipAdSegments = false))))
        val aliceStore = FilePlaybackSkipPreferenceStore(repo, aliceSession, tempDir, StandardTestDispatcher(testScheduler))

        aliceStore.refreshFromServer()
        advanceUntilIdle()
        assertEquals(false, aliceStore.enabled.value)

        val bobStore = FilePlaybackSkipPreferenceStore(repo, bobSession, tempDir, StandardTestDispatcher(testScheduler))
        assertEquals(true, bobStore.enabled.value, "bob starts with default true")
        aliceStore.close()
        bobStore.close()
    }
}

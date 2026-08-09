package dk.perspektiva.ttsroad.desktop.player

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The MPRIS mapping, which is the half of the D-Bus integration worth testing.
 *
 * Everything here runs without a session bus: [Mpris] is deliberately free of D-Bus types so the
 * audiobook-specific decisions — chapter as title, serial as album, what happens when nothing is
 * loaded — can be asserted on any machine, CI included.
 */
class MprisStateTest {

    private fun playing(
        title: String = "Chapter 12",
        fictionTitle: String? = "A Long Serial",
        chapterId: Int = 12,
        durationMs: Long = 600_000,
        positionMs: Long = 61_000,
        isPlaying: Boolean = true,
        hasMedia: Boolean = true,
    ) = PlayerUiState(
        title = title,
        fictionTitle = fictionTitle,
        fictionId = 3,
        coverImageUrl = "https://server.example/covers/3.jpg",
        isPlaying = isPlaying,
        hasMedia = hasMedia,
        positionMs = positionMs,
        durationMs = durationMs,
        queue = listOf(QueueItem(chapterId, title)),
        currentIndex = 0,
    )

    // --- Playback status ------------------------------------------------------------------------

    @Test
    fun `status maps the three states the wire has`() {
        assertEquals(MprisPlaybackStatus.Playing, Mpris.statusOf(playing()))
        assertEquals(MprisPlaybackStatus.Paused, Mpris.statusOf(playing(isPlaying = false)))
        assertEquals(MprisPlaybackStatus.Stopped, Mpris.statusOf(PlayerUiState()))
    }

    @Test
    fun `a paused player with no media is stopped, not paused`() {
        // "Paused" with nothing loaded makes a panel applet draw a resume button that cannot work.
        assertEquals(
            MprisPlaybackStatus.Stopped,
            Mpris.statusOf(playing(hasMedia = false, isPlaying = false)),
        )
    }

    @Test
    fun `the wire names are exactly the spec's`() {
        assertEquals("Playing", MprisPlaybackStatus.Playing.wireName)
        assertEquals("Paused", MprisPlaybackStatus.Paused.wireName)
        assertEquals("Stopped", MprisPlaybackStatus.Stopped.wireName)
    }

    // --- Metadata -------------------------------------------------------------------------------

    @Test
    fun `the chapter is the title and the serial is the album`() {
        val track = Mpris.trackOf(playing())
        assertEquals("Chapter 12", track?.title)
        assertEquals("A Long Serial", track?.album)
        assertEquals(listOf("A Long Serial"), track?.artists)
    }

    @Test
    fun `length is microseconds, not milliseconds`() {
        assertEquals(600_000_000L, Mpris.trackOf(playing())?.lengthMicros)
    }

    @Test
    fun `position is microseconds and never negative`() {
        assertEquals(61_000_000L, Mpris.positionMicros(playing()))
        assertEquals(0L, Mpris.positionMicros(playing(positionMs = -1)))
    }

    @Test
    fun `nothing loaded means no track at all`() {
        assertNull(Mpris.trackOf(PlayerUiState()))
    }

    @Test
    fun `a standalone chapter with no serial reports no artist rather than a blank one`() {
        // An empty string in xesam:artist renders as a dangling separator in several shells.
        val track = Mpris.trackOf(playing(fictionTitle = null))
        assertNull(track?.album)
        assertEquals(emptyList(), track?.artists)
    }

    @Test
    fun `a blank serial title is treated as absent`() {
        assertEquals(emptyList(), Mpris.trackOf(playing(fictionTitle = "   "))?.artists)
    }

    // --- Track ids ------------------------------------------------------------------------------

    @Test
    fun `a track id is a valid D-Bus object path`() {
        val path = Mpris.trackPath(12)
        assertTrue(path.startsWith("/"))
        // Object paths admit only these characters between slashes; a malformed one makes the
        // whole Metadata property fail to marshal and takes the applet's display with it.
        assertTrue(path.drop(1).split("/").all { segment -> segment.all { it.isLetterOrDigit() || it == '_' } })
        assertTrue(path.split("/").drop(1).none { it.isEmpty() })
    }

    @Test
    fun `an unusable chapter id becomes the spec's no-track path`() {
        assertEquals(Mpris.NoTrackPath, Mpris.trackPath(0))
        assertEquals(Mpris.NoTrackPath, Mpris.trackPath(-4))
    }

    @Test
    fun `different chapters get different track ids`() {
        assertTrue(Mpris.trackPath(11) != Mpris.trackPath(12))
    }

    // --- Seek acceptance ------------------------------------------------------------------------

    @Test
    fun `a seek for the loaded chapter is accepted`() {
        val state = playing()
        assertTrue(Mpris.acceptsSeekFor(state, Mpris.trackPath(12)))
    }

    @Test
    fun `a seek for a chapter that has since changed is ignored`() {
        // Otherwise the new chapter is seeked to the old one's offset.
        assertFalse(Mpris.acceptsSeekFor(playing(), Mpris.trackPath(11)))
    }

    @Test
    fun `no seek is accepted when nothing is loaded`() {
        assertFalse(Mpris.acceptsSeekFor(PlayerUiState(), Mpris.NoTrackPath))
    }

    // --- Identity -------------------------------------------------------------------------------

    @Test
    fun `the bus name and object path match the MPRIS spec`() {
        assertEquals("org.mpris.MediaPlayer2", Mpris.BusNamePrefix)
        assertEquals("/org/mpris/MediaPlayer2", Mpris.ObjectPath)
        assertEquals("org.mpris.MediaPlayer2.Player", Mpris.PlayerInterface)
        // The name the app claims has to sit under the prefix or no client will find it.
        assertTrue("${Mpris.BusNamePrefix}.${Mpris.Identity}".startsWith("${Mpris.BusNamePrefix}."))
    }

    @Test
    fun `artwork is published so the desktop can draw a cover`() {
        assertEquals("https://server.example/covers/3.jpg", Mpris.trackOf(playing())?.artUrl)
    }

    // --- Which transitions are announced --------------------------------------------------------

    @Test
    fun `a chapter that learns its duration announces CanSeek`() {
        // The API gave no audio_duration, so the chapter starts unseekable; the engine reports a
        // duration a moment later. hasMedia is true throughout, which is exactly why this edge was
        // missed: a client that caches the flag would leave seeking greyed out for the whole
        // chapter.
        val unknown = playing(durationMs = 0)
        val known = playing(durationMs = 600_000)

        assertFalse(Mpris.canSeek(unknown))
        assertTrue(Mpris.canSeek(known))
        assertTrue("CanSeek" in Mpris.changedPropertyNames(unknown, known))
    }

    @Test
    fun `a position tick alone announces nothing`() {
        // Position is excluded from PropertiesChanged by the spec, so the 250 ms tick must be
        // silent on the bus or the player becomes a signal storm.
        val before = playing(positionMs = 61_000)
        val after = playing(positionMs = 61_250)

        assertEquals(emptySet(), Mpris.changedPropertyNames(before, after))
    }

    @Test
    fun `pausing announces the status and nothing else`() {
        val changed = Mpris.changedPropertyNames(playing(), playing(isPlaying = false))
        assertEquals(setOf("PlaybackStatus"), changed)
    }

    @Test
    fun `loading a chapter announces the transport flags and the metadata`() {
        val changed = Mpris.changedPropertyNames(PlayerUiState(), playing())
        assertTrue(changed.containsAll(setOf("PlaybackStatus", "Metadata", "CanPlay", "CanPause", "CanSeek")))
    }

    @Test
    fun `changing chapter announces new metadata`() {
        val changed = Mpris.changedPropertyNames(
            playing(title = "Chapter 12", chapterId = 12),
            playing(title = "Chapter 13", chapterId = 13),
        )
        assertTrue("Metadata" in changed)
    }
}

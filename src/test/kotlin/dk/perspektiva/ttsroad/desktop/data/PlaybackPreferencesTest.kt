package dk.perspektiva.ttsroad.desktop.data

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Listening preferences: defaults, the migration rules for a file this build did not write, and
 * the promise that they are not tied to a session.
 */
class PlaybackPreferencesTest {

    // --- Defaults -------------------------------------------------------------------------------

    @Test
    fun `skip silence is off by default, matching mobile and the web player`() {
        assertFalse(PlaybackPreferences().skipSilence)
    }

    @Test
    fun `the defaults are normal speed, thirty seconds and no boost`() {
        val defaults = PlaybackPreferences()
        assertEquals(1f, defaults.speed)
        assertEquals(30, defaults.skipIntervalSeconds)
        assertEquals(VolumeBoost.Off, defaults.volumeBoost)
        assertEquals(30_000L, defaults.skipIntervalMs)
    }

    @Test
    fun `volume boost stops below the level that clips`() {
        // The ladder is deliberately capped; the assertion is here so raising it is a decision
        // somebody takes on purpose rather than a number that drifts.
        assertTrue(VolumeBoost.entries.all { it.gain <= 2.0 })
        assertEquals(1.0, VolumeBoost.Off.gain)
    }

    // --- Speed options --------------------------------------------------------------------------

    @Test
    fun `the offered speeds include 1_25x`() {
        assertTrue(PlaybackPreferences.SpeedPresets.any { kotlin.math.abs(it - 1.25f) < 0.001f })
    }

    @Test
    fun `a custom speed from another build stays selectable`() {
        // The requirement is "preserve an older custom value": a listener on 1.35x must not have it
        // silently rounded away the first time they open the menu in this build.
        val options = PlaybackPreferences.speedOptions(1.35f)
        assertTrue(options.any { kotlin.math.abs(it - 1.35f) < 0.001f })
        assertEquals(options.sorted(), options)
        assertEquals(PlaybackPreferences.SpeedPresets.size + 1, options.size)
    }

    @Test
    fun `a speed that is already a preset does not duplicate it`() {
        assertEquals(PlaybackPreferences.SpeedPresets, PlaybackPreferences.speedOptions(1.25f))
    }

    // --- Migration and clamping -----------------------------------------------------------------

    @Test
    fun `a stored speed outside the supported range is clamped, not rejected`() {
        assertEquals(3.0f, PlaybackPreferences.normaliseSpeed(9f))
        assertEquals(0.5f, PlaybackPreferences.normaliseSpeed(0.01f))
        assertEquals(1f, PlaybackPreferences.normaliseSpeed(Float.NaN))
    }

    @Test
    fun `a skip interval this build does not offer snaps to the nearest one`() {
        // Snapping rather than defaulting: a stored 20 means somebody chose 20, and 15 is a closer
        // answer to that than 30 is.
        assertEquals(15, PlaybackPreferences.normaliseSkipSeconds(20))
        assertEquals(60, PlaybackPreferences.normaliseSkipSeconds(120))
        assertEquals(10, PlaybackPreferences.normaliseSkipSeconds(0))
    }

    @Test
    fun `a file with no fields at all loads as the defaults`() {
        assertEquals(PlaybackPreferences(), StoredPlaybackPreferences().toPreferences())
    }

    @Test
    fun `an unknown volume boost falls back to off rather than to the loudest step`() {
        val stored = StoredPlaybackPreferences(volumeBoost = "Deafening")
        assertEquals(VolumeBoost.Off, stored.toPreferences().volumeBoost)
    }

    @Test
    fun `a volume boost is matched case-insensitively`() {
        assertEquals(
            VolumeBoost.Medium,
            StoredPlaybackPreferences(volumeBoost = "medium").toPreferences().volumeBoost,
        )
    }

    @Test
    fun `an out-of-range stored file is migrated on the way in`() {
        val stored = StoredPlaybackPreferences(speed = 42f, skipIntervalSeconds = 7, skipSilence = true)
        val loaded = stored.toPreferences()
        assertEquals(3.0f, loaded.speed)
        assertEquals(10, loaded.skipIntervalSeconds)
        assertTrue(loaded.skipSilence)
    }

    // --- The file store -------------------------------------------------------------------------

    @Test
    fun `preferences survive a restart`(@TempDir dir: File) {
        val file = dir.resolve("playback.json")
        FilePlaybackPreferencesStore(file).update {
            it.copy(speed = 1.5f, skipIntervalSeconds = 15, skipSilence = true, volumeBoost = VolumeBoost.Low)
        }

        // A second store over the same file is what "after a restart" means here.
        val reloaded = FilePlaybackPreferencesStore(file).preferences.value
        assertEquals(1.5f, reloaded.speed)
        assertEquals(15, reloaded.skipIntervalSeconds)
        assertTrue(reloaded.skipSilence)
        assertEquals(VolumeBoost.Low, reloaded.volumeBoost)
    }

    @Test
    fun `a missing file is not an error`(@TempDir dir: File) {
        val store = FilePlaybackPreferencesStore(dir.resolve("absent.json"))
        assertEquals(PlaybackPreferences(), store.preferences.value)
    }

    @Test
    fun `a corrupt file degrades to the defaults instead of failing startup`(@TempDir dir: File) {
        val file = dir.resolve("playback.json")
        file.writeText("{ this is not json")
        assertEquals(PlaybackPreferences(), FilePlaybackPreferencesStore(file).preferences.value)
    }

    @Test
    fun `an unusable stored value is clamped on write as well as on read`(@TempDir dir: File) {
        val file = dir.resolve("playback.json")
        val store = FilePlaybackPreferencesStore(file)
        store.update { it.copy(speed = 99f, skipIntervalSeconds = 3) }
        // Never in memory either, so nothing hands the engine a rate it cannot honour.
        assertEquals(3.0f, store.preferences.value.speed)
        assertEquals(10, store.preferences.value.skipIntervalSeconds)
    }

    @Test
    fun `the preferences file holds no credential and no server address`(@TempDir dir: File) {
        val file = dir.resolve("playback.json")
        FilePlaybackPreferencesStore(file).update { it.copy(speed = 2f) }
        val text = file.readText()
        assertFalse(text.contains("http", ignoreCase = true))
        assertFalse(text.contains("token", ignoreCase = true))
    }

    @Test
    fun `preferences live outside the session file, so signing out cannot reset them`(@TempDir dir: File) {
        // The guarantee is structural rather than behavioural: this store has no reference to a
        // session at all, and its file is a different one.
        val prefsFile = dir.resolve("playback.json")
        FilePlaybackPreferencesStore(prefsFile).update { it.copy(speed = 2f) }
        assertTrue(prefsFile.isFile)
        assertFalse(prefsFile.name.contains("session"))
        assertEquals(2f, FilePlaybackPreferencesStore(prefsFile).preferences.value.speed)
    }

    // --- Per-serial rates -------------------------------------------------------------------------

    @Test
    fun `a serial without its own rate simply uses the default`() {
        val preferences = PlaybackPreferences(speed = 1.25f, fictionSpeeds = mapOf(7 to 2f))

        assertEquals(2f, preferences.speedFor(7))
        assertEquals(1.25f, preferences.speedFor(8))
        assertEquals(1.25f, preferences.speedFor(0), "no serial loaded is not a serial with no rate")
    }

    @Test
    fun `setting and clearing one serial's rate leaves the others and the default alone`() {
        val start = PlaybackPreferences(speed = 1.25f, fictionSpeeds = mapOf(7 to 2f))

        val added = start.withFictionSpeed(8, 0.75f)
        assertEquals(mapOf(7 to 2f, 8 to 0.75f), added.fictionSpeeds)

        val cleared = added.withFictionSpeed(7, null)
        assertEquals(mapOf(8 to 0.75f), cleared.fictionSpeeds)
        assertEquals(1.25f, cleared.speed, "the default is not what the player was changing")
    }

    @Test
    fun `a serial rate that is out of range is snapped rather than handed to the engine`() {
        val store = InMemoryPlaybackPreferencesStore()

        store.update { it.withFictionSpeed(7, 9f).withFictionSpeed(8, -1f) }

        assertEquals(PlaybackPreferences.MaxSpeed, store.preferences.value.fictionSpeeds[7])
        assertEquals(PlaybackPreferences.MinSpeed, store.preferences.value.fictionSpeeds[8])
    }

    @Test
    fun `per-serial rates are bounded, oldest first`() {
        // This file is written for the life of the install; an unbounded map would leak on exactly
        // the machines of the people who use the app most.
        val store = InMemoryPlaybackPreferencesStore()

        store.update { start ->
            (1..PlaybackPreferences.MaxFictionSpeeds + 5).fold(start) { acc, id ->
                acc.withFictionSpeed(id, 1.5f)
            }
        }

        val kept = store.preferences.value.fictionSpeeds
        assertEquals(PlaybackPreferences.MaxFictionSpeeds, kept.size)
        assertFalse(kept.containsKey(1), "the rate set longest ago is the one to forget")
        assertTrue(kept.containsKey(PlaybackPreferences.MaxFictionSpeeds + 5))
    }

    @Test
    fun `per-serial rates round-trip through the file, and a nonsense key is dropped`(@TempDir dir: File) {
        val file = dir.resolve("playback.json")
        FilePlaybackPreferencesStore(file).update { it.withFictionSpeed(7, 1.5f) }

        assertEquals(mapOf(7 to 1.5f), FilePlaybackPreferencesStore(file).preferences.value.fictionSpeeds)

        // A file from another build — or a hand-edited one — must load degraded, never throw.
        file.writeText("""{"version":1,"speed":1.0,"fictionSpeeds":{"7":1.5,"not-an-id":2.0,"0":3.0}}""")
        assertEquals(mapOf(7 to 1.5f), FilePlaybackPreferencesStore(file).preferences.value.fictionSpeeds)
    }
}

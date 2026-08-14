package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How much the output is amplified above unity.
 *
 * Capped at [VolumeBoost.High] on purpose. Digital gain above roughly 2× on already-mastered
 * speech clips the peaks rather than making them louder, and a listener who reaches for "louder"
 * because the narration is quiet is exactly the listener who will not recognise distortion as
 * something *this app* did. The ladder stops where the artefacts start.
 */
enum class VolumeBoost(val label: String, val gain: Double) {
    Off("Off", 1.0),
    Low("Low", 1.3),
    Medium("Medium", 1.6),
    High("High", 2.0),
}

/**
 * Listening settings that belong to this OS profile and output setup, not to a TTSRoad account.
 *
 * Deliberately persisted outside the session: signing out must not reset someone's speed and skip
 * interval. Two TTSRoad accounts used by the same OS user intentionally see the same values because
 * the file has no account key; a different OS user has a different config directory. Nothing here
 * is secret, and there is nowhere in the type to put something that is.
 */
data class PlaybackPreferences(
    val speed: Float = DefaultSpeed,
    val skipIntervalSeconds: Int = DefaultSkipSeconds,
    /**
     * Default **off**, matching mobile 0.9.0 and the web player.
     *
     * Skipping silence changes where a chapter's timings fall, so a listener who has never asked
     * for it must not find their read-along drifting after an update.
     */
    val skipSilence: Boolean = false,
    val volumeBoost: VolumeBoost = VolumeBoost.Off,
    /**
     * Rates that belong to one serial rather than to the listener in general.
     *
     * Different narrators want different paces, and a listener who slows down for a dense
     * translation should not have to remember to speed back up for the next book. [speed] stays the
     * default; an entry here overrides it while that serial is playing.
     *
     * Keyed by fiction id, which is safe to keep in this account-less file for the same reason the
     * rest of it is: a fiction is a **shared** server object, not a per-account one, so an id here
     * says nothing about who read it. Bounded, because it grows for the life of the install.
     */
    val fictionSpeeds: Map<Int, Float> = emptyMap(),
) {
    /** The rate to play [fictionId] at: its own, or the listener's default. */
    fun speedFor(fictionId: Int): Float = fictionSpeeds[fictionId] ?: speed

    /** Sets one serial's rate, or clears it back to the default with a null [speed]. */
    fun withFictionSpeed(fictionId: Int, speed: Float?): PlaybackPreferences {
        if (fictionId <= 0) return this
        val next = if (speed == null) fictionSpeeds - fictionId else fictionSpeeds + (fictionId to speed)
        return copy(fictionSpeeds = next)
    }

    companion object {
        /**
         * How many serials keep their own rate.
         *
         * A bound rather than a policy about *which* to forget: this file is written for the life
         * of the install, and an unbounded map would be a slow leak on exactly the machines of the
         * people who use the app most. Oldest-first, because insertion order is the only ordering
         * the stored map has and the least recently *set* is the least likely to be missed.
         */
        const val MaxFictionSpeeds: Int = 200

        const val DefaultSpeed: Float = 1f
        const val MinSpeed: Float = 0.5f
        const val MaxSpeed: Float = 3.0f

        const val DefaultSkipSeconds: Int = 30

        /** The skip intervals the UI offers. Anything else in a stored file is snapped to these. */
        val SkipIntervals: List<Int> = listOf(10, 15, 30, 45, 60)

        /**
         * The speeds the UI offers as one-tap choices.
         *
         * 1.25× is here because it is the single most-used non-unity rate on mobile and stepping
         * 1.0 → 1.5 skips straight past it.
         */
        val SpeedPresets: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

        /**
         * The presets, plus [current] when it is not one of them.
         *
         * A value that arrived from an older build — or from a future one with a longer ladder —
         * stays selectable instead of being silently rounded to the nearest preset the moment the
         * user opens the menu. Losing someone's 1.35× because this build does not offer it is a
         * worse outcome than one odd-looking entry.
         */
        fun speedOptions(current: Float): List<Float> {
            val normalised = normaliseSpeed(current)
            if (SpeedPresets.any { it.isSameSpeedAs(normalised) }) return SpeedPresets
            return (SpeedPresets + normalised).sorted()
        }

        fun normaliseSpeed(speed: Float): Float =
            if (speed.isNaN()) DefaultSpeed else speed.coerceIn(MinSpeed, MaxSpeed)

        /**
         * Nearest offered interval.
         *
         * Snapping rather than clamping: a stored 20 means a build that offered 20, and 15 is a
         * closer answer to what that listener chose than either end of this build's ladder.
         */
        fun normaliseSkipSeconds(seconds: Int): Int =
            SkipIntervals.minByOrNull { kotlin.math.abs(it - seconds) } ?: DefaultSkipSeconds
    }
}

/** Float equality with a tolerance, so 1.2499999 and 1.25 are the same choice. */
private fun Float.isSameSpeedAs(other: Float): Boolean = kotlin.math.abs(this - other) < 0.001f

/** [PlaybackPreferences.skipIntervalSeconds] as the milliseconds the transport actually skips. */
val PlaybackPreferences.skipIntervalMs: Long get() = skipIntervalSeconds * 1000L

/**
 * The on-disk shape, kept separate from [PlaybackPreferences] so the in-memory type stays total.
 *
 * Every field is nullable and the enum is a plain string: a file written by an older build is
 * missing keys, and one written by a newer build can carry a `volumeBoost` this build has never
 * heard of. Both must load — degraded, not empty, and never as an exception during startup.
 */
internal data class StoredPlaybackPreferences(
    val version: Int? = null,
    val speed: Float? = null,
    val skipIntervalSeconds: Int? = null,
    val skipSilence: Boolean? = null,
    val volumeBoost: String? = null,
    /** JSON object keys are strings; a key that is not an id at all is dropped rather than fatal. */
    val fictionSpeeds: Map<String, Float>? = null,
) {
    fun toPreferences(): PlaybackPreferences = PlaybackPreferences(
        speed = PlaybackPreferences.normaliseSpeed(speed ?: PlaybackPreferences.DefaultSpeed),
        skipIntervalSeconds = PlaybackPreferences.normaliseSkipSeconds(
            skipIntervalSeconds ?: PlaybackPreferences.DefaultSkipSeconds,
        ),
        skipSilence = skipSilence ?: false,
        // An unrecognised name falls back to Off rather than to the loudest thing in the enum.
        volumeBoost = VolumeBoost.entries.firstOrNull { it.name.equals(volumeBoost, ignoreCase = true) }
            ?: VolumeBoost.Off,
        fictionSpeeds = fictionSpeeds.orEmpty()
            .mapNotNull { (key, value) -> key.toIntOrNull()?.takeIf { it > 0 }?.let { it to value } }
            .toMap(),
    )

    companion object {
        /** Bumped only when a field changes meaning; new *optional* fields do not need it. */
        const val CurrentVersion: Int = 1

        fun from(preferences: PlaybackPreferences) = StoredPlaybackPreferences(
            version = CurrentVersion,
            speed = preferences.speed,
            skipIntervalSeconds = preferences.skipIntervalSeconds,
            skipSilence = preferences.skipSilence,
            volumeBoost = preferences.volumeBoost.name,
            fictionSpeeds = preferences.fictionSpeeds.mapKeys { (id, _) -> id.toString() },
        )
    }
}

/** Seam so tests never touch the real user config directory. */
interface PlaybackPreferencesStore {
    val preferences: StateFlow<PlaybackPreferences>

    fun update(transform: (PlaybackPreferences) -> PlaybackPreferences)
}

/** In-memory store for tests and for the smoke test, which must not write to a real home. */
class InMemoryPlaybackPreferencesStore(
    initial: PlaybackPreferences = PlaybackPreferences(),
) : PlaybackPreferencesStore {
    private val _preferences = MutableStateFlow(initial)
    override val preferences: StateFlow<PlaybackPreferences> = _preferences.asStateFlow()

    @Synchronized
    override fun update(transform: (PlaybackPreferences) -> PlaybackPreferences) {
        _preferences.value = transform(_preferences.value).sanitised()
    }
}

/**
 * `playback.json` beside the session and window settings, written owner-only through [SecureFiles].
 *
 * Loaded once, eagerly, because the engine needs a speed before the first chapter prepares — a lazy
 * read would apply the saved rate one chapter late. Writes are fire-and-forget and failure is
 * logged rather than surfaced: losing a remembered skip interval is not worth an error dialog.
 */
class FilePlaybackPreferencesStore(
    private val file: File = defaultFile(),
) : PlaybackPreferencesStore {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(StoredPlaybackPreferences::class.java)

    private val _preferences = MutableStateFlow(read())
    override val preferences: StateFlow<PlaybackPreferences> = _preferences.asStateFlow()

    /**
     * Synchronised because `update` is read-modify-write and three threads reach it: the Compose
     * thread from Settings, the controller when it stores a speed, and dbus-java's reader thread
     * when a shell sets the volume. Two overlapping updates would otherwise each derive from the
     * same value and the later write would drop the other's field entirely.
     */
    @Synchronized
    override fun update(transform: (PlaybackPreferences) -> PlaybackPreferences) {
        val next = transform(_preferences.value).sanitised()
        if (next == _preferences.value) return
        _preferences.value = next
        runCatching { SecureFiles.writeAtomically(file, adapter.toJson(StoredPlaybackPreferences.from(next))) }
            .onFailure { AppLog.warn("could not write the playback settings file", it) }
    }

    private fun read(): PlaybackPreferences =
        runCatching { if (file.isFile) adapter.fromJson(file.readText()) else null }
            .onFailure { AppLog.warn("could not read the playback settings file", it) }
            .getOrNull()
            ?.toPreferences()
            ?: PlaybackPreferences()

    companion object {
        fun defaultFile(): File = FileSessionStore.configDir().resolve("playback.json")
    }
}

/**
 * Brings a caller-supplied value back into range.
 *
 * Applied on the way *in* rather than only on the way out, so a bad value can never sit in memory
 * being handed to the engine even if it never reaches disk.
 */
internal fun PlaybackPreferences.sanitised(): PlaybackPreferences = copy(
    speed = PlaybackPreferences.normaliseSpeed(speed),
    skipIntervalSeconds = PlaybackPreferences.normaliseSkipSeconds(skipIntervalSeconds),
    // Trimmed from the front: a `LinkedHashMap` keeps insertion order, so the oldest entry is the
    // one whose rate was set longest ago and is least likely to be missed.
    fictionSpeeds = fictionSpeeds
        .filterKeys { it > 0 }
        .mapValues { (_, rate) -> PlaybackPreferences.normaliseSpeed(rate) }
        .let { rates ->
            if (rates.size <= PlaybackPreferences.MaxFictionSpeeds) {
                rates
            } else {
                rates.entries.drop(rates.size - PlaybackPreferences.MaxFictionSpeeds)
                    .associate { it.key to it.value }
            }
        },
)

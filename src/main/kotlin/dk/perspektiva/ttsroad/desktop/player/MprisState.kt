package dk.perspektiva.ttsroad.desktop.player

/**
 * MPRIS `PlaybackStatus`, which is a closed set of three strings on the wire.
 *
 * Modelled rather than inlined because the mapping from [PlayerUiState] is the part worth testing,
 * and a test that asserts on an enum is not also asserting on a spelling.
 */
enum class MprisPlaybackStatus(val wireName: String) {
    Playing("Playing"),
    Paused("Paused"),
    Stopped("Stopped"),
}

/**
 * The subset of `xesam:`/`mpris:` metadata this player publishes, as plain Kotlin.
 *
 * Deliberately free of any D-Bus type. The mapping below is the interesting half of MPRIS — an
 * audiobook chapter is not a song, and deciding that the *chapter* is the title while the *serial*
 * is the album is a product decision that should be unit-tested on a machine with no session bus.
 * Converting this to `Variant`s is mechanical and lives in [MprisService].
 */
data class MprisTrack(
    /** A D-Bus object path, because that is what `mpris:trackid` is typed as. */
    val trackId: String,
    val title: String,
    val album: String?,
    val artists: List<String>,
    /** `mpris:length`, in **microseconds** — MPRIS's unit throughout, and not the app's. */
    val lengthMicros: Long,
    val artUrl: String?,
)

object Mpris {
    const val BusNamePrefix: String = "org.mpris.MediaPlayer2"
    const val ObjectPath: String = "/org/mpris/MediaPlayer2"
    const val RootInterface: String = "org.mpris.MediaPlayer2"
    const val PlayerInterface: String = "org.mpris.MediaPlayer2.Player"

    /** What the desktop shows as the application's name. */
    const val Identity: String = "TTSRoad"

    /**
     * Ties the MPRIS entry to the installed `.desktop` file, which is where Cinnamon's applet
     * takes the icon from. It is the basename without `.desktop`; the Linux packaging in phase 9
     * (#3) installs `TTSRoad.desktop` under that name.
     */
    const val DesktopEntry: String = "TTSRoad"

    /** The spec's placeholder path for "nothing is loaded". */
    const val NoTrackPath: String = "/org/mpris/MediaPlayer2/TrackList/NoTrack"

    private const val TrackPathPrefix: String = "/dk/perspektiva/ttsroad/chapter/"

    const val MicrosPerMilli: Long = 1_000L

    /**
     * A valid object path for [chapterId].
     *
     * Object paths admit only `[A-Za-z0-9_]` between slashes, so a non-positive or otherwise
     * unusable id becomes [NoTrackPath] rather than an malformed path — sending one of those makes
     * the whole `Metadata` property fail to marshal, which takes the applet's display with it.
     */
    fun trackPath(chapterId: Int): String =
        if (chapterId > 0) "$TrackPathPrefix$chapterId" else NoTrackPath

    fun statusOf(state: PlayerUiState): MprisPlaybackStatus = when {
        !state.hasMedia -> MprisPlaybackStatus.Stopped
        state.isPlaying -> MprisPlaybackStatus.Playing
        else -> MprisPlaybackStatus.Paused
    }

    /**
     * The loaded chapter as MPRIS metadata, or null when nothing is loaded.
     *
     * The audiobook mapping, which is the decision this function exists to record:
     * - `xesam:title` is the **chapter**, because that is what the panel shows in one line;
     * - `xesam:album` is the **serial**, which is what an album is;
     * - `xesam:artist` is also the serial. There is no author in the mobile API's payload, and an
     *   empty artist renders as a dangling separator in several shells, so repeating the serial
     *   reads better than a blank. It is a display choice, not a claim about authorship.
     */
    fun trackOf(state: PlayerUiState): MprisTrack? {
        if (!state.hasMedia) return null
        val chapterId = state.queue.getOrNull(state.currentIndex)?.chapterId ?: 0
        return MprisTrack(
            trackId = trackPath(chapterId),
            title = state.title,
            album = state.fictionTitle,
            artists = listOfNotNull(state.fictionTitle?.takeIf { it.isNotBlank() }),
            lengthMicros = state.durationMs.coerceAtLeast(0L) * MicrosPerMilli,
            // The cover is served unauthenticated, and the session bus is the user's own login
            // session — the same place the window itself is drawn. It does name the private
            // server's host to other processes in that session, which is the cost of the desktop
            // being able to draw artwork at all; nothing authenticating travels with it.
            artUrl = state.coverImageUrl,
        )
    }

    fun positionMicros(state: PlayerUiState): Long =
        state.positionMs.coerceAtLeast(0L) * MicrosPerMilli

    /**
     * Whether a seek request is worth honouring.
     *
     * MPRIS clients send absolute positions for a track id they last saw, and a stale id means the
     * chapter changed under them — applying it would seek the *new* chapter to the old one's
     * offset.
     */
    fun acceptsSeekFor(state: PlayerUiState, trackId: String): Boolean =
        state.hasMedia && trackId == trackOf(state)?.trackId
}

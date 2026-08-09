package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.data.AppLog
import dk.perspektiva.ttsroad.desktop.data.PlaybackPreferencesStore
import dk.perspektiva.ttsroad.desktop.data.VolumeBoost
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * `org.mpris.MediaPlayer2` — Raise and Quit, and the flags saying whether they work.
 *
 * Method names are capitalised because D-Bus member names are, and dbus-java derives them from the
 * Kotlin/Java method name directly.
 */
@DBusInterfaceName(Mpris.RootInterface)
interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

/** `org.mpris.MediaPlayer2.Player` — the transport the panel applet and the media keys drive. */
@DBusInterfaceName(Mpris.PlayerInterface)
interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()

    /** Relative, in microseconds; negative seeks backwards. */
    fun Seek(offsetMicros: Long)

    /** Absolute, in microseconds, for a track the caller last saw. */
    fun SetPosition(trackId: DBusPath, positionMicros: Long)

    fun OpenUri(uri: String)

    /**
     * Emitted when the position jumps for a reason the client did not cause.
     *
     * Without it a panel applet's progress bar keeps interpolating from where it thought it was,
     * so a ±30 s skip in the app shows up as a bar that is wrong until the next poll.
     */
    class Seeked(path: String, positionMicros: Long) : DBusSignal(path, positionMicros)
}

/**
 * The MPRIS presence: one exported object answering both interfaces plus `Properties`.
 *
 * Everything here is best-effort by construction. [createOrNull] returns null when there is no
 * session bus — a headless box, a `su` shell, macOS, Windows — and every callback into the
 * controller is wrapped, because a media player that fails to start because the desktop's media
 * applet is absent would be a worse bug than having no applet integration at all.
 *
 * Threading: dbus-java calls these methods on its own reader thread, so they land on the engine
 * from a third thread alongside the Compose thread and the controller's own scope. That is already
 * the situation the engine implementations are written for — both of the real ones synchronise
 * their transport internally — and nothing here holds a lock while calling out.
 */
class MprisService private constructor(
    private val connection: DBusConnection,
    private val busName: String,
    private val controller: PlaybackController,
    private val preferences: PlaybackPreferencesStore,
    private val onRaise: () -> Unit,
    private val onQuit: () -> Unit,
) : AutoCloseable {

    private var publisher: Job? = null

    /** The exported object. One instance answers root, player and properties at the same path. */
    private inner class ExportedPlayer : MprisRoot, MprisPlayer, Properties {

        override fun getObjectPath(): String = Mpris.ObjectPath

        override fun Raise() = guard { onRaise() }

        override fun Quit() = guard { onQuit() }

        override fun Next() = guard { controller.skipToNextChapter() }

        override fun Previous() = guard { controller.skipToPreviousChapter() }

        override fun Pause() = guard {
            if (controller.state.value.isPlaying) controller.togglePlayPause()
        }

        override fun Play() = guard {
            val current = controller.state.value
            if (current.hasMedia && !current.isPlaying) controller.togglePlayPause()
        }

        override fun PlayPause() = guard { controller.togglePlayPause() }

        override fun Stop() = guard { controller.stop() }

        override fun Seek(offsetMicros: Long) = guard {
            controller.skipBy(offsetMicros / Mpris.MicrosPerMilli)
        }

        override fun SetPosition(trackId: DBusPath, positionMicros: Long) = guard {
            // A stale track id means the chapter changed since the client read it; honouring it
            // would seek the new chapter to the old one's offset.
            if (Mpris.acceptsSeekFor(controller.state.value, trackId.path)) {
                controller.seekTo(positionMicros / Mpris.MicrosPerMilli)
            }
        }

        /**
         * Not supported, and it says so by doing nothing.
         *
         * Every URI this player can open is bearer-protected and belongs to the signed-in server;
         * accepting an arbitrary one from the session bus would be an open redirect into the
         * authenticated client.
         */
        override fun OpenUri(uri: String) = Unit

        /**
         * Returns the [Variant] itself, **not** `variant.value`.
         *
         * `Get` is declared to return `v`, and dbus-java re-wraps a bare value in an *unqualified*
         * Variant — which throws for `Metadata`, whose `a{sv}` signature only exists because
         * [playerProperties] attached it. Handing back the already-qualified Variant keeps the
         * signature all the way to the wire. `GetAll` never had the problem: its return type is
         * `a{sv}` and the Variants survive in the map.
         */
        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
            allProperties(interfaceName)[propertyName] as A

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) = guard {
            when (propertyName) {
                "Rate" -> (value as? Double)?.let { controller.setSpeed(it.toFloat()) }
                // The shell's slider is continuous and the app's boost is a four-step ladder, so
                // this snaps. Reporting the snapped value back (below) is what keeps the slider
                // from drifting away from what is actually being played.
                "Volume" -> (value as? Double)?.let { requested ->
                    val nearest = VolumeBoost.entries.minByOrNull { kotlin.math.abs(it.gain - requested) }
                    if (nearest != null) preferences.update { it.copy(volumeBoost = nearest) }
                }
                else -> Unit
            }
        }

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = allProperties(interfaceName)
    }

    private val exported = ExportedPlayer()

    private fun allProperties(interfaceName: String): Map<String, Variant<*>> =
        when (interfaceName) {
            Mpris.RootInterface -> rootProperties()
            Mpris.PlayerInterface -> playerProperties()
            else -> emptyMap()
        }

    private fun rootProperties(): Map<String, Variant<*>> = mapOf(
        "CanQuit" to Variant(true),
        "CanRaise" to Variant(true),
        "HasTrackList" to Variant(false),
        "Identity" to Variant(Mpris.Identity),
        "DesktopEntry" to Variant(Mpris.DesktopEntry),
        // Empty, and deliberately so: OpenUri is not implemented, and advertising a scheme the
        // player will silently ignore is how a file manager ends up "opening" audio into nothing.
        "SupportedUriSchemes" to Variant(emptyList<String>(), "as"),
        "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
    )

    private fun playerProperties(): Map<String, Variant<*>> {
        val state = controller.state.value
        val track = Mpris.trackOf(state)
        return mapOf(
            "PlaybackStatus" to Variant(Mpris.statusOf(state).wireName),
            "LoopStatus" to Variant("None"),
            "Rate" to Variant(state.speed.toDouble()),
            "Shuffle" to Variant(false),
            "Metadata" to Variant(metadataOf(track), "a{sv}"),
            "Volume" to Variant(preferences.preferences.value.volumeBoost.gain),
            "Position" to Variant(Mpris.positionMicros(state)),
            // A backend with no rate control reports a degenerate range rather than a range it
            // cannot honour, so a client that offers a rate slider does not offer a dead one.
            "MinimumRate" to Variant(if (state.canChangeSpeed) MinRate else 1.0),
            "MaximumRate" to Variant(if (state.canChangeSpeed) MaxRate else 1.0),
            "CanGoNext" to Variant(state.hasNext),
            "CanGoPrevious" to Variant(state.hasPrevious),
            "CanPlay" to Variant(state.hasMedia),
            "CanPause" to Variant(state.hasMedia),
            "CanSeek" to Variant(state.hasMedia && state.durationMs > 0),
            "CanControl" to Variant(true),
        )
    }

    private fun metadataOf(track: MprisTrack?): Map<String, Variant<*>> {
        if (track == null) {
            // The spec's "nothing loaded" shape: a valid trackid and nothing else. An empty map
            // makes some shells keep displaying the previous track indefinitely.
            return mapOf("mpris:trackid" to Variant(DBusPath(Mpris.NoTrackPath), "o"))
        }
        return buildMap {
            put("mpris:trackid", Variant(DBusPath(track.trackId), "o"))
            put("xesam:title", Variant(track.title))
            put("mpris:length", Variant(track.lengthMicros))
            track.album?.takeIf { it.isNotBlank() }?.let { put("xesam:album", Variant(it)) }
            if (track.artists.isNotEmpty()) {
                put("xesam:artist", Variant(ArrayList(track.artists), "as"))
            }
            track.artUrl?.takeIf { it.isNotBlank() }?.let { put("mpris:artUrl", Variant(it)) }
        }
    }

    /**
     * Starts mirroring [PlaybackController.state] onto the bus.
     *
     * Only the properties that actually changed are announced. `Position` is deliberately never in
     * a `PropertiesChanged` — the spec excludes it, because a player emitting it on every tick is
     * a signal storm — so a discontinuity is reported as `Seeked` instead and clients interpolate
     * between the two.
     */
    private fun startPublishing(scope: CoroutineScope) {
        publisher = scope.launch {
            launch {
                var previous: PlayerUiState? = null
                controller.state.collect { state ->
                    val last = previous
                    previous = state
                    if (last == null) return@collect

                    val changed = changedProperties(last, state)
                    if (changed.isNotEmpty()) emitPropertiesChanged(changed)
                    if (isDiscontinuity(last, state)) emitSeeked(Mpris.positionMicros(state))
                }
            }

            // Volume is the one published property that does not live in PlayerUiState, so the
            // state collector above can never see it change. Without this a shell that asks for a
            // continuous 1.5 keeps displaying 1.5 while playback actually uses the snapped 1.6 —
            // and a boost changed in Settings never reaches the panel at all.
            launch {
                var previous: VolumeBoost? = null
                preferences.preferences.collect { current ->
                    val last = previous
                    previous = current.volumeBoost
                    if (last == null || last == current.volumeBoost) return@collect
                    emitPropertiesChanged(mapOf("Volume" to Variant(current.volumeBoost.gain)))
                }
            }
        }
    }

    /** Names from the pure mapping, paired with the current wire values. */
    private fun changedProperties(before: PlayerUiState, after: PlayerUiState): Map<String, Variant<*>> {
        val names = Mpris.changedPropertyNames(before, after)
        if (names.isEmpty()) return emptyMap()
        val all = playerProperties()
        return names.mapNotNull { name -> all[name]?.let { name to it } }.toMap()
    }

    /**
     * Whether the position moved by more than playback alone explains.
     *
     * The tick is 250 ms and the rate tops out at 3×, so anything past a second is a seek, a skip,
     * or a chapter change rather than time passing.
     */
    private fun isDiscontinuity(before: PlayerUiState, after: PlayerUiState): Boolean {
        if (!after.hasMedia) return false
        if (before.currentIndex != after.currentIndex) return true
        val delta = after.positionMs - before.positionMs
        return delta < 0 || delta > DiscontinuityMs
    }

    private fun emitPropertiesChanged(changed: Map<String, Variant<*>>) {
        runCatching {
            connection.sendMessage(
                Properties.PropertiesChanged(
                    Mpris.ObjectPath,
                    Mpris.PlayerInterface,
                    changed,
                    emptyList(),
                ),
            )
        }.onFailure { AppLog.warn("could not publish an MPRIS property change", it) }
    }

    private fun emitSeeked(positionMicros: Long) {
        runCatching { connection.sendMessage(MprisPlayer.Seeked(Mpris.ObjectPath, positionMicros)) }
            .onFailure { AppLog.warn("could not publish an MPRIS seek", it) }
    }

    /**
     * Runs [body], swallowing anything it throws.
     *
     * An exception escaping into dbus-java's reader thread is returned to the caller as a D-Bus
     * error and, worse, can take the thread down — which would silently end MPRIS for the rest of
     * the session. Nothing the applet asks for is important enough to risk that.
     */
    private inline fun guard(body: () -> Unit) {
        runCatching { body() }.onFailure { AppLog.warn("an MPRIS request failed", it) }
    }

    override fun close() {
        publisher?.cancel()
        runCatching { connection.unExportObject(Mpris.ObjectPath) }
        runCatching { connection.releaseBusName(busName) }
        runCatching { connection.disconnect() }
    }

    companion object {
        private const val MinRate = 0.5
        private const val MaxRate = 3.0
        private const val DiscontinuityMs = 1_500L

        /**
         * Connects to the session bus and exports the player, or returns null.
         *
         * Null is a normal outcome, not a failure: there is no session bus on Windows or macOS, in
         * a bare SSH session, or under some sandboxes. The diagnostic goes to [AppLog] so a
         * developer can see *why* the panel shows nothing, and the app carries on with a fully
         * working player that simply is not on the bus.
         */
        fun createOrNull(
            controller: PlaybackController,
            preferences: PlaybackPreferencesStore,
            scope: CoroutineScope,
            onRaise: () -> Unit = {},
            onQuit: () -> Unit = {},
        ): MprisService? {
            // Catching Throwable, not Exception: a missing transport provider surfaces as a
            // ServiceConfigurationError, and an incompatible JDK as a LinkageError. Neither is an
            // Exception, and either would otherwise abort startup.
            return runCatching {
                val connection = DBusConnectionBuilder.forSessionBus().build()
                val busName = claimBusName(connection)
                    ?: run {
                        runCatching { connection.disconnect() }
                        return@runCatching null
                    }
                val service = MprisService(connection, busName, controller, preferences, onRaise, onQuit)
                connection.exportObject(Mpris.ObjectPath, service.exported)
                service.startPublishing(scope)
                service
            }.getOrElse { error ->
                AppLog.warn("no MPRIS integration on this desktop (${error.javaClass.simpleName})", error)
                null
            }
        }

        /**
         * Takes `org.mpris.MediaPlayer2.TTSRoad`, or the spec's per-instance variant if a second
         * copy of the app already holds it.
         *
         * Two windows both publishing under one name would fight over the panel's display, which
         * is exactly what the `.instanceN` suffix in the MPRIS spec exists to prevent.
         */
        private fun claimBusName(connection: DBusConnection): String? {
            val preferred = "${Mpris.BusNamePrefix}.${Mpris.Identity}"
            runCatching {
                connection.requestBusName(preferred)
                return preferred
            }
            val instance = "$preferred.instance${ProcessHandle.current().pid()}"
            return runCatching {
                connection.requestBusName(instance)
                instance
            }.getOrElse {
                AppLog.warn("could not claim an MPRIS bus name", it)
                null
            }
        }
    }
}

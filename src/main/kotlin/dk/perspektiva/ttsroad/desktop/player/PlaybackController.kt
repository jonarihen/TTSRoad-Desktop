package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerUiState(
    val title: String = "Nothing playing",
    val fictionTitle: String? = null,
    val coverImageUrl: String? = null,
    val isPlaying: Boolean = false,
    val hasMedia: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
)

/**
 * Playback abstraction for the desktop client. The UI only ever drives playback through this
 * interface — exactly like the Android [PlaybackController] wraps Media3 — so the audio backend
 * can be swapped without touching the UI.
 *
 * TODO(audio backend): wire a real implementation. The audio URLs are bearer-protected MP3s, so
 * whatever player is used must send the Authorization header from [TtsRoadRepository.authHeaderValue].
 * Recommended options, in order:
 *   1. VLCJ (uk.co.caprica:vlcj) — robust streaming MP3; pass the header via media options
 *      (":http-user-agent", ":http-..."), or run a tiny localhost proxy that injects auth.
 *   2. Stream to a temp file with OkHttp (auth header attached) and play with JavaFX Media.
 * Keep [StubPlaybackController] as the no-op default until then so the UI stays runnable.
 */
interface PlaybackController {
    val state: StateFlow<PlayerUiState>
    suspend fun play(chapter: ChapterSummary, fiction: FictionSummary?)
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun skipBy(deltaMs: Long)
    fun setSpeed(speed: Float)
    fun stop()
}

/**
 * Default no-op controller: tracks just enough UI state to exercise the player screen, but does
 * not decode audio yet. Replace with a VLCJ/JavaFX-backed implementation (see interface docs).
 */
class StubPlaybackController(
    @Suppress("unused") private val repository: TtsRoadRepository,
) : PlaybackController {
    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    override suspend fun play(chapter: ChapterSummary, fiction: FictionSummary?) {
        _state.value = PlayerUiState(
            title = chapter.resolvedTitle,
            fictionTitle = fiction?.title ?: chapter.resolvedFictionTitle,
            coverImageUrl = fiction?.coverImageUrl ?: chapter.resolvedCoverUrl,
            hasMedia = true,
            isPlaying = false,
            durationMs = ((chapter.audioDuration ?: 0.0) * 1000).toLong(),
            positionMs = (chapter.resolvedPositionSeconds * 1000).toLong(),
        )
    }

    override fun togglePlayPause() {
        _state.value = _state.value.copy(isPlaying = !_state.value.isPlaying)
    }

    override fun seekTo(positionMs: Long) {
        _state.value = _state.value.copy(positionMs = positionMs.coerceAtLeast(0L))
    }

    override fun skipBy(deltaMs: Long) {
        seekTo(_state.value.positionMs + deltaMs)
    }

    override fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed)
    }

    override fun stop() {
        _state.value = PlayerUiState()
    }
}

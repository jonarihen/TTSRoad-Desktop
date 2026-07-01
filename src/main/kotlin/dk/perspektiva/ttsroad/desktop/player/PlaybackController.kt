package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.data.ChapterSummary
import dk.perspektiva.ttsroad.desktop.data.FictionSummary
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import java.io.File
import java.io.IOException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

data class PlayerUiState(
    val title: String = "Nothing playing",
    val fictionTitle: String? = null,
    val coverImageUrl: String? = null,
    val isPlaying: Boolean = false,
    val hasMedia: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val error: String? = null,
)

/**
 * Playback abstraction for the desktop client — the UI only ever drives playback through this
 * interface, so the audio backend can be swapped without touching UI code.
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
 * Downloads the bearer-protected chapter MP3 to a temp file with OkHttp (auth header attached),
 * decodes it via the mp3spi/JLayer `javax.sound.sampled` SPI, and plays the resulting PCM through
 * a [SourceDataLine]. Seeking re-decodes from the start of the (already-local) temp file and
 * discards up to the target offset, since a streamed MP3 decoder has no random-access index —
 * simple and exact, at the cost of a brief pause on long seeks.
 */
class Mp3PlaybackController(private val repository: TtsRoadRepository) : PlaybackController {
    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient()

    private var playJob: Job? = null
    private var tempFile: File? = null

    @Volatile private var wantsPlaying = false
    @Volatile private var seekRequestMs: Long? = null

    override suspend fun play(chapter: ChapterSummary, fiction: FictionSummary?) {
        playJob?.cancel()
        deleteTempFile()
        wantsPlaying = false
        seekRequestMs = null

        val audio = chapter.audio
        val startMs = (chapter.resolvedPositionSeconds * 1000).toLong()
        _state.value = PlayerUiState(
            title = chapter.resolvedTitle,
            fictionTitle = fiction?.title ?: chapter.resolvedFictionTitle,
            coverImageUrl = (fiction?.coverImageUrl ?: chapter.resolvedCoverUrl)?.let(repository::resolveUrl),
            durationMs = ((chapter.audioDuration ?: 0.0) * 1000).toLong(),
            positionMs = startMs,
            error = if (audio == null) "This chapter has no audio yet" else null,
        )
        if (audio == null) return

        playJob = scope.launch {
            try {
                val file = download(audio.url)
                tempFile = file
                wantsPlaying = true
                _state.update { it.copy(hasMedia = true, isPlaying = true) }
                runPlaybackLoop(file, startMs, chapter)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Playback failed", isPlaying = false) }
            }
        }
    }

    override fun togglePlayPause() {
        if (!_state.value.hasMedia) return
        wantsPlaying = !wantsPlaying
        _state.update { it.copy(isPlaying = wantsPlaying) }
    }

    override fun seekTo(positionMs: Long) {
        if (!_state.value.hasMedia) return
        val clamped = positionMs.coerceIn(0L, _state.value.durationMs.coerceAtLeast(0L))
        seekRequestMs = clamped
        _state.update { it.copy(positionMs = clamped) }
    }

    override fun skipBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    override fun setSpeed(speed: Float) {
        // Variable-rate playback isn't implemented by the SourceDataLine backend; tracked for UI only.
        _state.update { it.copy(speed = speed) }
    }

    override fun stop() {
        playJob?.cancel()
        playJob = null
        wantsPlaying = false
        deleteTempFile()
        _state.value = PlayerUiState()
    }

    private suspend fun CoroutineScope.runPlaybackLoop(file: File, startMs: Long, chapter: ChapterSummary) {
        var decoded = openDecodedStream(file)
        val format = decoded.format
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val line: SourceDataLine
        try {
            line = AudioSystem.getLine(info) as SourceDataLine
            line.open(format)
        } catch (e: Exception) {
            decoded.close()
            throw e
        }

        var bytesPlayed = 0L
        if (startMs > 0) {
            val toSkip = msToBytes(startMs, format)
            skipFully(decoded, toSkip)
            bytesPlayed = toSkip
        }

        val buffer = ByteArray(8192)
        var lastSavedMs = 0L
        var reachedEnd = false
        try {
            while (isActive) {
                seekRequestMs?.let { targetMs ->
                    seekRequestMs = null
                    decoded.close()
                    decoded = openDecodedStream(file)
                    line.flush()
                    val toSkip = msToBytes(targetMs, format)
                    skipFully(decoded, toSkip)
                    bytesPlayed = toSkip
                    reachedEnd = false
                }

                if (!wantsPlaying) {
                    if (line.isRunning) line.stop()
                    delay(80)
                    continue
                }
                if (!line.isRunning) line.start()

                val n = decoded.read(buffer)
                if (n < 0) {
                    reachedEnd = true
                    break
                }
                line.write(buffer, 0, n)
                bytesPlayed += n
                val posMs = bytesToMs(bytesPlayed, format)
                _state.update { it.copy(positionMs = posMs, isPlaying = true) }

                if (posMs - lastSavedMs > 10_000) {
                    lastSavedMs = posMs
                    saveProgress(chapter, posMs, isPlayed = false)
                }
            }
            if (reachedEnd) line.drain()
        } finally {
            line.stop()
            line.close()
            decoded.close()
        }

        if (reachedEnd) {
            val durationMs = _state.value.durationMs
            _state.update { it.copy(isPlaying = false, positionMs = durationMs) }
            saveProgress(chapter, durationMs, isPlayed = true)
        }
    }

    private suspend fun saveProgress(chapter: ChapterSummary, positionMs: Long, isPlayed: Boolean) {
        runCatching {
            repository.saveProgress(
                fictionId = chapter.resolvedFictionId,
                chapterId = chapter.resolvedChapterId,
                positionSeconds = positionMs / 1000.0,
                isPlayed = isPlayed,
            )
        }
    }

    private fun download(url: String): File {
        val resolved = repository.resolveUrl(url)
        val auth = repository.authHeaderValue() ?: throw IOException("Not logged in")
        val request = Request.Builder().url(resolved).header("Authorization", auth).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to download audio (HTTP ${response.code})")
            val file = File.createTempFile("ttsroad-", ".mp3")
            file.deleteOnExit()
            response.body!!.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            return file
        }
    }

    private fun deleteTempFile() {
        tempFile?.delete()
        tempFile = null
    }

    private fun openDecodedStream(file: File): AudioInputStream {
        val fileStream = AudioSystem.getAudioInputStream(file)
        val base = fileStream.format
        val target = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            base.sampleRate,
            16,
            base.channels,
            base.channels * 2,
            base.sampleRate,
            false,
        )
        return AudioSystem.getAudioInputStream(target, fileStream)
    }

    private fun msToBytes(ms: Long, format: AudioFormat): Long {
        val bytesPerSecond = format.frameRate * format.frameSize
        val raw = (ms / 1000.0 * bytesPerSecond).toLong()
        return raw - (raw % format.frameSize)
    }

    private fun bytesToMs(bytes: Long, format: AudioFormat): Long {
        val bytesPerSecond = format.frameRate * format.frameSize
        return (bytes / bytesPerSecond * 1000).toLong()
    }

    private fun skipFully(stream: AudioInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }
}

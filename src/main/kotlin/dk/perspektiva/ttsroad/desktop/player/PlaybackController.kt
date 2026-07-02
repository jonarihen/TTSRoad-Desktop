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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

data class QueueItem(
    val chapterId: Int,
    val title: String,
)

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
    val queue: List<QueueItem> = emptyList(),
    val currentIndex: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
)

/**
 * Playback abstraction for the desktop client — the UI only ever drives playback through this
 * interface, so the audio backend can be swapped without touching UI code.
 */
interface PlaybackController {
    val state: StateFlow<PlayerUiState>
    suspend fun play(chapter: ChapterSummary, fiction: FictionSummary?)

    /**
     * Play a whole fiction as a queue, starting at [startChapterId] — enables next/previous,
     * auto-advance, and the up-next list (mirrors the Android client's playQueue).
     */
    suspend fun playQueue(chapters: List<ChapterSummary>, startChapterId: Int, fiction: FictionSummary?)
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun skipBy(deltaMs: Long)
    fun skipToNextChapter()
    fun skipToPreviousChapter()
    fun skipToQueueIndex(index: Int)
    fun setSpeed(speed: Float)
    fun stop()
}

/**
 * Downloads the bearer-protected chapter MP3 to a temp file with OkHttp (auth header attached),
 * decodes it via the mp3spi/JLayer `javax.sound.sampled` SPI, and plays the resulting PCM through
 * a [SourceDataLine]. Seeking re-decodes from the start of the (already-local) temp file and
 * discards up to the target offset, since a streamed MP3 decoder has no random-access index —
 * simple and exact, at the cost of a brief pause on long seeks.
 *
 * The queue is a plain list of chapters: one playback job walks it, downloading each chapter as
 * it becomes current and advancing when decoding reaches end-of-stream.
 */
class Mp3PlaybackController(private val repository: TtsRoadRepository) : PlaybackController {
    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient()

    private var playJob: Job? = null
    private var tempFile: File? = null

    private var queue: List<ChapterSummary> = emptyList()
    private var queueFiction: FictionSummary? = null

    @Volatile private var queueIndex = 0
    @Volatile private var wantsPlaying = false
    @Volatile private var seekRequestMs: Long? = null

    override suspend fun play(chapter: ChapterSummary, fiction: FictionSummary?) {
        if (chapter.audio == null) {
            stopJob()
            queue = emptyList()
            queueFiction = fiction
            _state.value = PlayerUiState(
                title = chapter.resolvedTitle,
                fictionTitle = fiction?.title ?: chapter.resolvedFictionTitle,
                coverImageUrl = (fiction?.coverImageUrl ?: chapter.resolvedCoverUrl)?.let(repository::resolveUrl),
                error = "This chapter has no audio yet",
            )
            return
        }
        queue = listOf(chapter)
        queueFiction = fiction
        beginPlayback(0, resumeMsOf(chapter))
    }

    override suspend fun playQueue(chapters: List<ChapterSummary>, startChapterId: Int, fiction: FictionSummary?) {
        val playable = chapters.filter { it.audio != null }
        if (playable.isEmpty()) {
            _state.update { it.copy(error = "No playable chapters yet") }
            return
        }
        queue = playable
        queueFiction = fiction
        val startIndex = playable.indexOfFirst { it.resolvedChapterId == startChapterId }.coerceAtLeast(0)
        beginPlayback(startIndex, resumeMsOf(playable[startIndex]))
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

    override fun skipToNextChapter() {
        val next = queueIndex + 1
        if (next in queue.indices) scope.launch { beginPlayback(next, 0L) }
    }

    override fun skipToPreviousChapter() {
        // Audiobook "previous": restart the current chapter unless we're near its start.
        if (_state.value.positionMs > 5_000 || queueIndex == 0) {
            seekTo(0L)
        } else {
            scope.launch { beginPlayback(queueIndex - 1, 0L) }
        }
    }

    override fun skipToQueueIndex(index: Int) {
        if (index in queue.indices && index != queueIndex) {
            scope.launch { beginPlayback(index, 0L) }
        }
    }

    override fun setSpeed(speed: Float) {
        // Variable-rate playback isn't implemented by the SourceDataLine backend; tracked for UI only.
        _state.update { it.copy(speed = speed) }
    }

    override fun stop() {
        stopJob()
        queue = emptyList()
        queueFiction = null
        deleteTempFile()
        _state.value = PlayerUiState()
    }

    private fun stopJob() {
        playJob?.cancel()
        playJob = null
        wantsPlaying = false
    }

    private fun resumeMsOf(chapter: ChapterSummary): Long =
        (chapter.resolvedPositionSeconds * 1000).toLong().coerceAtLeast(0L)

    private suspend fun beginPlayback(startIndex: Int, startMs: Long) {
        playJob?.cancelAndJoin()
        deleteTempFile()
        wantsPlaying = false
        seekRequestMs = null
        queueIndex = startIndex
        publishMetadata(startIndex, startMs)

        playJob = scope.launch {
            var index = startIndex
            var positionMs = startMs
            while (isActive && index in queue.indices) {
                val chapter = queue[index]
                queueIndex = index
                publishMetadata(index, positionMs)
                val reachedEnd = try {
                    val file = download(chapter.audio!!.url)
                    tempFile = file
                    wantsPlaying = true
                    _state.update { it.copy(hasMedia = true, isPlaying = true) }
                    runPlaybackLoop(file, positionMs, chapter)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // a new beginPlayback/stop superseded this job; not an error
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: "Playback failed", isPlaying = false) }
                    return@launch
                }
                if (!reachedEnd) return@launch // paused-forever is handled inside; only cancel exits false

                val durationMs = _state.value.durationMs
                saveProgress(chapter, durationMs, isPlayed = true)
                deleteTempFile()
                if (index == queue.lastIndex) {
                    _state.update { it.copy(isPlaying = false, positionMs = durationMs) }
                    return@launch
                }
                index++
                positionMs = 0L
            }
        }
    }

    private fun publishMetadata(index: Int, positionMs: Long) {
        val chapter = queue.getOrNull(index) ?: return
        val fiction = queueFiction
        _state.value = PlayerUiState(
            title = chapter.resolvedTitle,
            fictionTitle = fiction?.title ?: chapter.resolvedFictionTitle,
            coverImageUrl = (fiction?.coverImageUrl ?: chapter.resolvedCoverUrl)?.let(repository::resolveUrl),
            durationMs = ((chapter.audioDuration ?: 0.0) * 1000).toLong(),
            positionMs = positionMs,
            speed = _state.value.speed,
            queue = queue.map { QueueItem(it.resolvedChapterId, it.resolvedTitle) },
            currentIndex = index,
            hasNext = index < queue.lastIndex,
            hasPrevious = index > 0,
        )
    }

    /** Decodes and plays one chapter; returns true when it reached end-of-stream naturally. */
    private suspend fun CoroutineScope.runPlaybackLoop(file: File, startMs: Long, chapter: ChapterSummary): Boolean {
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
        return reachedEnd
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

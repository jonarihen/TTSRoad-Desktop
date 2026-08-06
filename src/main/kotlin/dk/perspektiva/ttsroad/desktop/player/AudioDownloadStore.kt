package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import java.io.File
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Seam for "get the bytes of a chapter onto local disk".
 *
 * Chapter MP3s are bearer-protected, so this is not a plain URL fetch. Isolating it means the
 * playback controller can be tested without a network, and a future on-disk cache / range-resume
 * implementation slots in behind the same two methods.
 */
interface AudioDownloadStore {
    /** Blocking; call from an IO context. Returns a local file holding the whole chapter. */
    fun download(url: String): File

    /** Releases a file previously handed out by [download]. */
    fun release(file: File?)
}

/**
 * Downloads the whole chapter to a temp file with the `Authorization` header attached.
 *
 * The [client] is injected rather than constructed here so the app has exactly one OkHttp
 * instance (connection pool, thread pool, timeouts) instead of one per subsystem.
 */
class HttpAudioDownloadStore(
    private val client: OkHttpClient,
    private val repository: TtsRoadRepository,
) : AudioDownloadStore {
    override fun download(url: String): File {
        val resolved = repository.resolveUrl(url)
        val auth = repository.authHeaderValue() ?: throw IOException("Not logged in")
        val request = Request.Builder().url(resolved).header("Authorization", auth).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to download audio (HTTP ${response.code})")
            val file = File.createTempFile("ttsroad-", ".mp3")
            file.deleteOnExit()
            response.body.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            return file
        }
    }

    override fun release(file: File?) {
        file?.delete()
    }
}

package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.data.SessionEnd
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.parseSessionEnd
import java.io.File
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A 401 on the audio path.
 *
 * Carries the parsed reason rather than a status code because the audio route returns the same
 * structured 401 body as the JSON API (`app/routers/feeds.py` defers to the same auth helper), and
 * "this device was signed out" is exactly as useful here as it is on a library call. Typed so the
 * playback controller can tell it apart from "the CDN hiccuped" — one ends the session, the other
 * is a retryable error.
 */
class SessionExpiredException(val sessionEnd: SessionEnd) : IOException(sessionEnd.message)

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
 * Downloads the whole chapter to a temp file.
 *
 * The `Authorization` header is *not* set here: [client] is the app's single OkHttp instance and
 * carries the auth interceptor, which attaches the token if — and only if — the resolved audio URL
 * is on the signed-in server's origin. That is the same rule cover images get, and it is why an
 * absolute audio URL pointing somewhere else cannot leak the credential.
 */
class HttpAudioDownloadStore(
    private val client: OkHttpClient,
    private val repository: TtsRoadRepository,
) : AudioDownloadStore {
    override fun download(url: String): File {
        val resolved = repository.resolveUrl(url)
        // Fail fast rather than fetching a 401 we already know is coming.
        if (repository.authHeaderValue() == null) throw IOException("Not signed in")
        val request = Request.Builder().url(resolved).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw SessionExpiredException(parseSessionEnd(response.body.string()))
            if (!response.isSuccessful) throw IOException("Could not download this chapter (HTTP ${response.code})")
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

package dk.perspektiva.ttsroad.desktop.player

import dk.perspektiva.ttsroad.desktop.data.SessionEnd
import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.parseSessionEnd
import java.io.File
import java.io.IOException
import java.io.InputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * A 401 on the audio path.
 *
 * Carries the parsed reason rather than a status code because the audio route returns the same
 * structured 401 body as the JSON API, and "this device was signed out" is exactly as useful here
 * as it is on a library call. Typed so the controller can tell it apart from "the CDN hiccuped" —
 * one ends the session, the other is retryable.
 */
class SessionExpiredException(val sessionEnd: SessionEnd) : IOException(sessionEnd.message)

/**
 * Where a chapter's bytes come from.
 *
 * This replaces `AudioDownloadStore`, whose only method was "give me a File holding the whole
 * chapter" — the reason nothing could play until everything had arrived. A [MediaStream] hands out
 * bytes as they come, so the engine can start on the first few kilobytes.
 *
 * It is also the seam Phase 7's offline cache slots into: a cached chapter is a
 * [FileMediaSource], a partially cached one is a source that reads locally and falls through to
 * the network, and neither changes a line of engine code.
 */
interface MediaSource {
    /** Opens a read handle positioned at byte 0. The caller closes it. */
    fun open(): MediaStream
}

/**
 * One read handle over a chapter's bytes.
 *
 * Deliberately byte-oriented rather than sample-oriented: this is the *container*, and decoding it
 * is the engine's job. GStreamer's `appsrc` pulls exactly this shape.
 */
interface MediaStream : AutoCloseable {
    /** Total length in bytes, or -1 when the server did not say. */
    val length: Long

    /** Whether [seek] can be expected to work — a server without range support answers false. */
    val isSeekable: Boolean

    /** Reads up to [count] bytes. Returns the number read, or -1 at end of stream. */
    fun read(buffer: ByteArray, offset: Int, count: Int): Int

    /** Repositions to an absolute byte offset. Returns false if the source could not do it. */
    fun seek(position: Long): Boolean
}

/**
 * Streams a bearer-protected chapter over HTTP, using range requests to seek.
 *
 * The `Authorization` header is *not* set here. [client] is the app's single OkHttp instance and
 * carries the auth interceptor, which attaches the token if — and only if — the resolved URL is on
 * the signed-in server's origin. That is the same rule cover images get, and it is why an absolute
 * audio URL pointing somewhere else cannot leak the credential. Duplicating the header here would
 * quietly defeat that check, which is the main reason this does not use GStreamer's own HTTP
 * source (see docs/adr/0002-playback-engine.md).
 */
class HttpMediaSource(
    private val client: OkHttpClient,
    private val repository: TtsRoadRepository,
    private val url: String,
) : MediaSource {

    override fun open(): MediaStream {
        val resolved = repository.resolveUrl(url)
        // Fail fast rather than fetching a 401 we already know is coming.
        if (repository.authHeaderValue() == null) throw IOException("Not signed in")
        return HttpMediaStream(client, resolved)
    }

    private class HttpMediaStream(
        private val client: OkHttpClient,
        private val url: String,
    ) : MediaStream {

        private var response: Response = request(from = 0)
        private var body: InputStream = response.body.byteStream()
        private var position: Long = 0

        override var length: Long = contentLengthOf(response)
            private set

        /**
         * A server that honoured the opening request's `Range` proves it can do it again. We ask
         * for `bytes=0-` up front precisely to learn this before the user tries to seek.
         */
        override val isSeekable: Boolean = response.code == 206

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            val n = body.read(buffer, offset, count)
            if (n > 0) position += n
            return n
        }

        override fun seek(position: Long): Boolean {
            if (position == this.position) return true
            if (!isSeekable || position < 0) return false
            if (length in 0..<position) return false
            // A fresh ranged request is the whole trick: the old implementation re-decoded from
            // byte 0 and threw away everything before the target.
            val next = runCatching { request(from = position) }.getOrNull() ?: return false
            if (next.code != 206) {
                next.close()
                return false
            }
            closeQuietly()
            response = next
            body = next.body.byteStream()
            this.position = position
            return true
        }

        override fun close() = closeQuietly()

        private fun closeQuietly() {
            runCatching { body.close() }
            runCatching { response.close() }
        }

        private fun request(from: Long): Response {
            val call = client.newCall(
                Request.Builder()
                    .url(url)
                    // Always ranged, even at 0, so the opening response tells us whether seeking
                    // will work at all.
                    .header("Range", "bytes=$from-")
                    .build(),
            )
            val response = call.execute()
            if (response.code == 401) {
                val end = parseSessionEnd(response.body.string())
                response.close()
                throw SessionExpiredException(end)
            }
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                throw IOException("Could not play this chapter (HTTP $code)")
            }
            return response
        }

        private companion object {
            /**
             * Total length, not the length of this response: a `206` reports the *range* it is
             * returning, and its `Content-Range` names the whole. Getting this wrong makes an
             * engine think a chapter ends where a seek happened to start.
             */
            fun contentLengthOf(response: Response): Long {
                response.header("Content-Range")
                    ?.substringAfter('/', "")
                    ?.takeIf { it.isNotBlank() && it != "*" }
                    ?.toLongOrNull()
                    ?.let { return it }
                return response.body.contentLength()
            }
        }
    }
}

/** A chapter already on disk — a test fixture today, a cache entry in Phase 7. */
class FileMediaSource(private val file: File) : MediaSource {
    override fun open(): MediaStream = FileMediaStream(file)

    private class FileMediaStream(file: File) : MediaStream {
        private val handle = java.io.RandomAccessFile(file, "r")
        override val length: Long = handle.length()
        override val isSeekable: Boolean = true

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int =
            handle.read(buffer, offset, count)

        override fun seek(position: Long): Boolean {
            if (position < 0 || position > length) return false
            handle.seek(position)
            return true
        }

        override fun close() = handle.close()
    }
}

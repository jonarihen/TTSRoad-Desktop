package dk.perspektiva.ttsroad.desktop.download

import dk.perspektiva.ttsroad.desktop.data.TtsRoadRepository
import dk.perspektiva.ttsroad.desktop.data.describeNetworkFailure
import dk.perspektiva.ttsroad.desktop.data.parseSessionEnd
import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request

/** How a transfer ended. */
sealed interface DownloadResult {
    /** Bytes are on disk, validated, and renamed into place. */
    data class Success(val bytes: Long) : DownloadResult

    data class Failed(val failure: DownloadFailure) : DownloadResult

    /** Cancelled by the user or by shutdown. The `.part` file is kept so a retry resumes. */
    data object Cancelled : DownloadResult
}

/**
 * Fetches one chapter's audio to disk.
 *
 * Deliberately one chapter, no queue, no state: the concurrency and the index live in
 * [DownloadManager], and keeping them out of here is what makes the interesting parts — resume,
 * validation, the atomic rename — testable against a `MockWebServer` without a scheduler.
 *
 * The transfer goes through the app's single [OkHttpClient], so the auth interceptor attaches the
 * bearer token if and only if the resolved URL is on the signed-in server's origin. That is the
 * same rule the streaming path follows, and the reason this does not build its own client or set
 * its own `Authorization` header.
 */
class ChapterDownloader(
    private val client: OkHttpClient,
    private val repository: TtsRoadRepository,
    private val storage: DownloadStorage,
    /** Validates that the finished bytes are really decodable audio. */
    private val validator: DownloadValidator = Mp3HeaderValidator,
) {

    /**
     * Downloads [audioUrl] into [fileName], resuming an existing `.part` if there is one.
     *
     * [onProgress] is called with (bytesSoFar, totalOrZero) as data arrives, throttled by the
     * caller's own appetite — it is invoked per network chunk, which is frequent, so a UI consumer
     * should conflate.
     */
    suspend fun download(
        audioUrl: String,
        fileName: String,
        expectedBytes: Long = 0L,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): DownloadResult {
        val target = runCatching { storage.resolve(fileName) }
            .getOrElse { return DownloadResult.Failed(DownloadFailure.Corrupt(it.message ?: "bad file name")) }
        val part = runCatching { storage.resolve(fileName + DownloadStorage.PartSuffix) }
            .getOrElse { return DownloadResult.Failed(DownloadFailure.Corrupt(it.message ?: "bad file name")) }

        if (!storage.prepare()) {
            // Not fatal on its own — the directory may exist and merely refuse a permission change.
            if (!storage.root.isDirectory) {
                return DownloadResult.Failed(DownloadFailure.Transient("Could not create the download folder"))
            }
        }

        // A part file longer than what the server says exists is not a resume point, it is debris
        // from a different encoding of this chapter. Starting over is the only safe reading.
        var alreadyHave = part.takeIf { it.isFile }?.length() ?: 0L
        if (expectedBytes > 0 && alreadyHave > expectedBytes) {
            runCatching { part.delete() }
            alreadyHave = 0L
        }

        val completePart = expectedBytes > 0L && alreadyHave == expectedBytes
        val remaining = if (expectedBytes > 0) expectedBytes - alreadyHave else 0L
        if (!completePart && !storage.hasRoomFor(remaining)) {
            return DownloadResult.Failed(
                DownloadFailure.OutOfSpace("Not enough free disk space for this chapter"),
            )
        }

        return runCatching {
            if (completePart) {
                // A previous process may have finished writing and crashed before the atomic move.
                // Reissuing Range: bytes=<length>- would receive 416 forever; validate and promote
                // the complete bytes locally instead.
                RandomAccessFile(part, "rw").use { it.fd.sync() }
                promote(part, target, alreadyHave)
            } else {
                transfer(audioUrl, part, target, alreadyHave, expectedBytes, onProgress)
            }
        }
            .getOrElse { error ->
                when (error) {
                    is SessionExpiredDownloadException ->
                        DownloadResult.Failed(DownloadFailure.SessionExpired(error.message.orEmpty()))

                    is OutOfSpaceException ->
                        DownloadResult.Failed(DownloadFailure.OutOfSpace(error.message.orEmpty()))

                    is GoneException ->
                        DownloadResult.Failed(DownloadFailure.Gone(error.message.orEmpty()))

                    is kotlinx.coroutines.CancellationException -> throw error

                    else -> DownloadResult.Failed(DownloadFailure.Transient(describeNetworkFailure(error)))
                }
            }
    }

    private suspend fun transfer(
        audioUrl: String,
        part: File,
        target: File,
        alreadyHave: Long,
        expectedBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): DownloadResult {
        val resolved = repository.resolveUrl(audioUrl)
        if (repository.authHeaderValue() == null) {
            throw SessionExpiredDownloadException("Not signed in")
        }

        val request = Request.Builder()
            .url(resolved)
            // Only ask for a range when there is something to resume. A bare `bytes=0-` would work
            // too, but asking for it unconditionally makes a 200 from a range-less server look like
            // a failed resume rather than a normal full download.
            .apply { if (alreadyHave > 0) header("Range", "bytes=$alreadyHave-") }
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401) {
                val end = parseSessionEnd(response.body.string())
                // A download uses the same credential as API and playback calls. Treating this as
                // a row-local failure would leave a revoked session browsing normally until its
                // next request happened to notice; end it at the first authoritative 401.
                repository.endSession(end)
                throw SessionExpiredDownloadException(end.message)
            }
            if (response.code == 404 || response.code == 410) {
                throw GoneException("This chapter is no longer on the server")
            }
            if (!response.isSuccessful) {
                throw IOException("The server refused the download (HTTP ${response.code})")
            }

            // A server that ignored the Range header answers 200 and starts from byte 0, so
            // appending would corrupt the file. Truncate and take the whole thing instead.
            val appending = alreadyHave > 0 && response.code == 206
            val startAt = if (appending) alreadyHave else 0L

            val total = when {
                expectedBytes > 0 -> expectedBytes
                else -> response.body.contentLength().takeIf { it > 0 }?.plus(startAt) ?: 0L
            }

            var written = startAt
            RandomAccessFile(part, "rw").use { file ->
                if (!SecureFiles.restrictToOwner(part.toPath())) {
                    throw IOException("Could not make the partial download owner-only")
                }
                file.seek(startAt)
                if (!appending) file.setLength(0)

                val buffer = ByteArray(BufferBytes)
                val source = response.body.byteStream()
                while (true) {
                    if (!coroutineContext.isActive) return DownloadResult.Cancelled
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    try {
                        file.write(buffer, 0, read)
                    } catch (e: IOException) {
                        // "No space left on device" arrives as a plain IOException; the user needs
                        // to be told to free space, not offered a Retry that cannot work.
                        if (e.message?.contains("space", ignoreCase = true) == true) {
                            throw OutOfSpaceException("The disk is full")
                        }
                        throw e
                    }
                    written += read
                    onProgress(written, total)
                }
                // The bytes are in the page cache until this returns. Without it a power loss
                // leaves a file the index calls complete and the decoder calls truncated.
                file.fd.sync()
            }

            // Validate *before* the rename, because the rename is what makes it "Offline". A short
            // file means the connection dropped cleanly mid-transfer, which HTTP does not report
            // as an error.
            if (total > 0 && written != total) {
                throw IOException("The download ended early (${written} of $total bytes)")
            }
            return promote(part, target, written)
        }
    }

    private fun promote(part: File, target: File, written: Long): DownloadResult {
        if (!validator.looksDecodable(part)) {
            // The bytes are wrong rather than incomplete, so a retry must start from zero.
            runCatching { part.delete() }
            return DownloadResult.Failed(
                DownloadFailure.Corrupt("The downloaded audio could not be read"),
            )
        }

        // Atomic: a reader sees the old file or the new one, never a partial one. This is the
        // single moment a chapter becomes playable offline.
        runCatching {
            Files.move(
                part.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse { throw IOException("Could not finish the download", it) }

        SecureFiles.restrictToOwner(target.toPath())
        return DownloadResult.Success(written)
    }

    private companion object {
        const val BufferBytes = 64 * 1024
    }
}

/** A 401 during a download. Separate from the playback one so neither package depends on the other. */
class SessionExpiredDownloadException(message: String) : IOException(message)

/** A 404/410: the chapter is gone, and retrying the same request cannot help. */
class GoneException(message: String) : IOException(message)

/** Whether a finished file is really the audio it claims to be. */
fun interface DownloadValidator {
    fun looksDecodable(file: File): Boolean
}

/**
 * A cheap structural check that the file is an MP3, not an error page.
 *
 * The failure this catches is specific and common: a proxy, a captive portal or a misconfigured
 * server answers 200 with an HTML body, the bytes arrive intact, the length matches nothing, and
 * the chapter is marked Offline. It then fails to play, days later, with no network to blame.
 *
 * Deliberately *not* a full decode. Decoding every finished download would cost seconds per chapter
 * and pull the audio backend into the download path; checking that the file starts with an ID3 tag
 * or an MPEG frame sync rejects the realistic failures for the price of reading a few bytes.
 */
object Mp3HeaderValidator : DownloadValidator {
    override fun looksDecodable(file: File): Boolean {
        if (!file.isFile || file.length() < MinimumBytes) return false
        val head = ByteArray(3)
        runCatching {
            file.inputStream().use { if (it.read(head) != head.size) return false }
        }.getOrElse { return false }

        // "ID3" — a tagged MP3, which is what the server produces.
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            return true
        }
        // A bare MPEG frame sync: eleven set bits.
        val syncedFirst = head[0].toInt() and 0xFF
        val syncedSecond = head[1].toInt() and 0xFF
        return syncedFirst == 0xFF && (syncedSecond and 0xE0) == 0xE0
    }

    /** Below this nothing is a chapter, and the check would be reading past the end anyway. */
    private const val MinimumBytes = 1024L
}

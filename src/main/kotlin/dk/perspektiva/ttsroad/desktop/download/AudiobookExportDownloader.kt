package dk.perspektiva.ttsroad.desktop.download

import dk.perspektiva.ttsroad.desktop.data.AudiobookExport
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface AudiobookDownloadResult {
    data class Success(val file: File, val bytes: Long) : AudiobookDownloadResult
    data class Failed(val failure: DownloadFailure) : AudiobookDownloadResult
}

fun interface AudiobookExportDownloader {
    suspend fun download(
        export: AudiobookExport,
        destination: File,
        onProgress: (Long, Long) -> Unit,
    ): AudiobookDownloadResult
}

object UnavailableAudiobookExportDownloader : AudiobookExportDownloader {
    override suspend fun download(
        export: AudiobookExport,
        destination: File,
        onProgress: (Long, Long) -> Unit,
    ): AudiobookDownloadResult = AudiobookDownloadResult.Failed(
        DownloadFailure.Transient("Audiobook downloads are unavailable"),
    )
}

/**
 * Resumable, atomic download of one server-produced M4B into a user-selected path.
 *
 * The shared OkHttp client owns authentication and applies it only to the signed-in server's
 * origin. A partial sits beside the chosen destination and includes the export id in its name, so
 * choosing the same path later resumes the same volume without confusing two server exports.
 */
class HttpAudiobookExportDownloader(
    private val client: OkHttpClient,
    private val repository: TtsRoadRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AudiobookExportDownloader {
    override suspend fun download(
        export: AudiobookExport,
        destination: File,
        onProgress: (Long, Long) -> Unit,
    ): AudiobookDownloadResult = withContext(dispatcher) {
        when {
            export.id <= 0 || !export.downloadable || export.downloadUrl.isNullOrBlank() ->
                AudiobookDownloadResult.Failed(DownloadFailure.Gone("This export is not downloadable"))

            export.playableInApp ->
                AudiobookDownloadResult.Failed(DownloadFailure.Corrupt("The export contract was invalid"))

            else -> downloadReady(export, destination, onProgress)
        }
    }

    private suspend fun downloadReady(
        export: AudiobookExport,
        destination: File,
        onProgress: (Long, Long) -> Unit,
    ): AudiobookDownloadResult {
        val target = destination.absoluteFile.normalize()
        val parent = target.parentFile
            ?: return AudiobookDownloadResult.Failed(DownloadFailure.Corrupt("Choose a destination folder"))
        val part = File(parent, ".${target.name}.ttsroad-${export.id}.part")
        if (target.name.isBlank() || Files.isSymbolicLink(target.toPath()) || Files.isSymbolicLink(part.toPath())) {
            return AudiobookDownloadResult.Failed(DownloadFailure.Corrupt("The destination is not a regular file"))
        }
        runCatching { Files.createDirectories(parent.toPath()) }
            .getOrElse {
                return AudiobookDownloadResult.Failed(
                    DownloadFailure.Transient("Could not create the destination folder"),
                )
            }

        var alreadyHave = part.takeIf(File::isFile)?.length() ?: 0L
        if (export.sizeBytes > 0L && alreadyHave > export.sizeBytes) {
            runCatching { Files.deleteIfExists(part.toPath()) }
            alreadyHave = 0L
        }
        val remaining = (export.sizeBytes - alreadyHave).coerceAtLeast(0L)
        val usable = parent.usableSpace
        val required = remaining.coerceAtMost(Long.MAX_VALUE - FreeSpaceMarginBytes) + FreeSpaceMarginBytes
        if (remaining > 0L && usable > 0L && usable < required) {
            return AudiobookDownloadResult.Failed(
                DownloadFailure.OutOfSpace("Not enough free disk space for this audiobook"),
            )
        }

        return try {
            if (export.sizeBytes > 0L && alreadyHave == export.sizeBytes) {
                RandomAccessFile(part, "rw").use { it.fd.sync() }
                promote(part, target, alreadyHave)
            } else {
                transfer(export, part, target, alreadyHave, onProgress)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: SessionExpiredDownloadException) {
            AudiobookDownloadResult.Failed(DownloadFailure.SessionExpired(failure.message.orEmpty()))
        } catch (failure: OutOfSpaceException) {
            AudiobookDownloadResult.Failed(DownloadFailure.OutOfSpace(failure.message.orEmpty()))
        } catch (failure: GoneException) {
            AudiobookDownloadResult.Failed(DownloadFailure.Gone(failure.message.orEmpty()))
        } catch (failure: Exception) {
            AudiobookDownloadResult.Failed(DownloadFailure.Transient(describeNetworkFailure(failure)))
        }
    }

    private suspend fun transfer(
        export: AudiobookExport,
        part: File,
        target: File,
        alreadyHave: Long,
        onProgress: (Long, Long) -> Unit,
    ): AudiobookDownloadResult {
        if (repository.authHeaderValue() == null) throw SessionExpiredDownloadException("Not signed in")
        val request = Request.Builder()
            .url(repository.resolveUrl(requireNotNull(export.downloadUrl)))
            .apply { if (alreadyHave > 0L) header("Range", "bytes=$alreadyHave-") }
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401) {
                val end = parseSessionEnd(response.body.string())
                repository.endSession(end)
                throw SessionExpiredDownloadException(end.message)
            }
            if (response.code == 404 || response.code == 410) {
                throw GoneException("This audiobook export is no longer on the server")
            }
            if (!response.isSuccessful) throw IOException("The server refused the download (HTTP ${response.code})")

            val appending = alreadyHave > 0L && response.code == 206
            val startAt = if (appending) alreadyHave else 0L
            val total = export.sizeBytes.takeIf { it > 0L }
                ?: response.body.contentLength().takeIf { it > 0L }?.plus(startAt)
                ?: 0L
            var written = startAt
            RandomAccessFile(part, "rw").use { output ->
                SecureFiles.restrictToOwner(part.toPath())
                output.seek(startAt)
                if (!appending) output.setLength(0L)
                val input = response.body.byteStream()
                val buffer = ByteArray(BufferBytes)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    try {
                        output.write(buffer, 0, read)
                    } catch (failure: IOException) {
                        if (failure.message?.contains("space", ignoreCase = true) == true) {
                            throw OutOfSpaceException("The disk is full")
                        }
                        throw failure
                    }
                    written += read
                    onProgress(written, total)
                }
                output.fd.sync()
            }
            if (total > 0L && written != total) {
                throw IOException("The download ended early ($written of $total bytes)")
            }
            return promote(part, target, written)
        }
    }

    private fun promote(part: File, target: File, bytes: Long): AudiobookDownloadResult {
        if (!M4bContainerValidator.looksValid(part)) {
            runCatching { Files.deleteIfExists(part.toPath()) }
            return AudiobookDownloadResult.Failed(
                DownloadFailure.Corrupt("The downloaded file was not a readable M4B container"),
            )
        }
        runCatching {
            Files.move(
                part.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse { throw IOException("Could not finish the audiobook download", it) }
        SecureFiles.restrictToOwner(target.toPath())
        return AudiobookDownloadResult.Success(target, bytes)
    }

    private companion object {
        const val BufferBytes = 64 * 1024
        const val FreeSpaceMarginBytes = 64L * 1024 * 1024
    }
}

/** A cheap rejection of HTML/error bodies: an ISO base-media file starts with an `ftyp` box. */
object M4bContainerValidator {
    fun looksValid(file: File): Boolean {
        if (!file.isFile || file.length() < MinimumBytes) return false
        val header = ByteArray(12)
        return runCatching {
            file.inputStream().use { input ->
                if (input.readNBytes(header, 0, header.size) != header.size) return false
            }
            val boxSize = header.take(4).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
            boxSize >= 8L && header.copyOfRange(4, 8).decodeToString() == "ftyp"
        }.getOrDefault(false)
    }

    private const val MinimumBytes = 32L
}

/** Never trusts a server-supplied path; only its last, cleaned filename is offered to the picker. */
fun suggestedAudiobookFileName(filename: String): String {
    val leaf = (filename.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001f\\u007f]"), "_")
        .trim()
        .takeIf { candidate -> candidate.any(Char::isLetterOrDigit) })
        ?: "audiobook.m4b"
    return if (leaf.endsWith(".m4b", ignoreCase = true)) leaf else "$leaf.m4b"
}

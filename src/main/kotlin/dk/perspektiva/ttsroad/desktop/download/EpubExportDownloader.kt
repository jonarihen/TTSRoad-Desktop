package dk.perspektiva.ttsroad.desktop.download

import dk.perspektiva.ttsroad.desktop.data.EpubExportSession
import dk.perspektiva.ttsroad.desktop.data.detailMessage
import dk.perspektiva.ttsroad.desktop.data.parseSessionEnd
import dk.perspektiva.ttsroad.desktop.data.redactSecrets
import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call

sealed interface EpubDownloadResult {
    data class Success(val file: File, val bytes: Long) : EpubDownloadResult
    data class Failed(val message: String) : EpubDownloadResult
}

class EpubExportOperation(private val session: EpubExportSession) {
    private val lock = Any()
    private var active = true
    private var call: Call? = null

    fun cancel() = synchronized(lock) {
        active = false
        call?.cancel()
        Unit
    }

    fun isCurrent(): Boolean = session.isCurrent() && synchronized(lock) { active }

    fun ensureCurrent() {
        if (!isCurrent()) throw CancellationException("EPUB export cancelled")
    }

    fun publish(block: () -> Unit): Boolean {
        var published = false
        session.publish {
            synchronized(lock) {
                if (active) {
                    block()
                    published = true
                }
            }
        }
        return published
    }

    internal fun newCall(): Call {
        ensureCurrent()
        val next = session.newCall()
        synchronized(lock) {
            if (!active) {
                next.cancel()
                throw CancellationException("EPUB export cancelled")
            }
            call = next
        }
        return next
    }

    internal suspend fun endSession(body: String) = session.endSession(parseSessionEnd(body))
}

fun interface EpubExportDownloader {
    suspend fun download(
        operation: EpubExportOperation,
        destination: File,
        onProgress: (Long, Long) -> Unit,
    ): EpubDownloadResult
}

class HttpEpubExportDownloader(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EpubExportDownloader {
    override suspend fun download(
        operation: EpubExportOperation,
        destination: File,
        onProgress: (Long, Long) -> Unit,
    ): EpubDownloadResult = withContext(dispatcher) {
        var part: Path? = null
        try {
            operation.ensureCurrent()
            coroutineContext.ensureActive()
            val target = destination.absoluteFile.normalize().toPath()
            val parent = target.parent
            require(parent != null && Files.isDirectory(parent)) { "Choose an existing destination folder" }
            require(!Files.isSymbolicLink(target) && !Files.isDirectory(target)) {
                "Choose a regular destination file"
            }
            part = Files.createTempFile(parent, ".ttsroad-epub-", ".part")
            if (!SecureFiles.restrictToOwner(part)) throw IOException("Cannot secure the temporary EPUB file")
            transfer(operation, part, target, onProgress)
        } catch (cancelled: CancellationException) {
            if (coroutineContext.isActive && !operation.isCurrent()) {
                EpubDownloadResult.Failed("EPUB export cancelled")
            } else {
                throw cancelled
            }
        } catch (failure: Exception) {
            coroutineContext.ensureActive()
            operation.ensureCurrent()
            EpubDownloadResult.Failed(
                redactSecrets(failure.message).takeIf { it.isNotBlank() } ?: "Could not save the EPUB",
            )
        } finally {
            part?.let { Files.deleteIfExists(it) }
        }
    }

    private suspend fun transfer(
        operation: EpubExportOperation,
        part: Path,
        target: Path,
        onProgress: (Long, Long) -> Unit,
    ): EpubDownloadResult = coroutineScope {
        val call = operation.newCall()
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                coroutineContext.ensureActive()
                operation.ensureCurrent()
                if (!response.isSuccessful) {
                    val body = response.body.byteStream().readNBytes(ErrorBytes).toString(Charsets.UTF_8)
                    if (response.code == 401) {
                        operation.endSession(body)
                        return@coroutineScope EpubDownloadResult.Failed(parseSessionEnd(body).message)
                    }
                    val detail = redactSecrets(detailMessage(body)).takeIf { it.isNotBlank() }
                    return@coroutineScope EpubDownloadResult.Failed(
                        detail ?: when (response.code) {
                            409 -> "This fiction has no text available for EPUB export"
                            404 -> "This server cannot export this fiction as EPUB"
                            else -> "The server refused the EPUB export (HTTP ${response.code})"
                        },
                    )
                }
                if (response.code != 200) throw IOException("The server returned an incomplete EPUB response")
                val total = response.body.contentLength()
                var written = 0L
                RandomAccessFile(part.toFile(), "rw").use { output ->
                    val input = response.body.byteStream()
                    val buffer = ByteArray(BufferBytes)
                    while (true) {
                        coroutineContext.ensureActive()
                        operation.ensureCurrent()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                    output.fd.sync()
                }
                if (written == 0L || (total >= 0L && written != total)) {
                    throw IOException("The EPUB download ended before the complete file arrived")
                }
                val header = part.toFile().inputStream().use { it.readNBytes(4) }
                if (!header.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) {
                    throw IOException("The downloaded file was not an EPUB archive")
                }
                coroutineContext.ensureActive()
                if (!operation.publish {
                        coroutineContext.ensureActive()
                        try {
                            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                ) throw CancellationException("EPUB export cancelled")
                EpubDownloadResult.Success(target.toFile(), written)
            }
        } finally {
            cancellation.cancel()
        }
    }

    private companion object {
        const val BufferBytes = 64 * 1024
        const val ErrorBytes = 16 * 1024
    }
}

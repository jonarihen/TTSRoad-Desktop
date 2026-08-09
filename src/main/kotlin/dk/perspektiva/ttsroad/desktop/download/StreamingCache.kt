package dk.perspektiva.ttsroad.desktop.download

import dk.perspektiva.ttsroad.desktop.data.AppDirectories
import dk.perspektiva.ttsroad.desktop.data.AppLog
import dk.perspektiva.ttsroad.desktop.data.StorageIdentity
import dk.perspektiva.ttsroad.desktop.data.chapterFileName
import dk.perspektiva.ttsroad.desktop.player.FileMediaSource
import dk.perspektiva.ttsroad.desktop.player.MediaSource
import dk.perspektiva.ttsroad.desktop.player.MediaStream
import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Measured rebuildable audio currently held for one account. */
data class StreamingCacheStats(val bytes: Long = 0L, val files: Int = 0)

/**
 * A bounded, account-namespaced cache populated while a chapter is streamed for playback.
 *
 * This is deliberately separate from [DownloadStorage]. A completed file here is an optimisation
 * the app may evict; it is never surfaced as a user-requested Offline download. The cache keeps no
 * URL, title, token or server filename — its entire identity is the already-namespaced chapter id.
 */
class StreamingCache(
    val root: File,
    private val validator: DownloadValidator = Mp3HeaderValidator,
    private val maxBytes: Long = DefaultMaxBytes,
    private val ownedDirectories: List<File> = listOf(root),
) {

    /** A validated completed source, or null when this chapter still needs the network. */
    @Synchronized
    fun sourceFor(chapterId: Int): MediaSource? {
        val file = runCatching { resolve(chapterFileName(chapterId)) }
            .onFailure { AppLog.warn("refusing an unsafe streaming cache path", it) }
            .getOrNull()
            ?: return null
        if (!file.isFile) return null
        if (!validator.looksDecodable(file)) {
            runCatching { Files.deleteIfExists(file.toPath()) }
            return null
        }
        // Last-modified is the LRU clock. Failure only makes eviction less exact, never unsafe.
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return FileMediaSource(file)
    }

    /**
     * Tees a sequential network stream into a `.part` file and promotes it only at clean EOF.
     *
     * Seeking abandons the cache attempt: filling arbitrary holes correctly would need a range map,
     * and a discarded rebuildable partial is preferable to a file that merely looks complete.
     * Playback itself continues unaffected if any cache write fails.
     */
    fun retaining(chapterId: Int, upstream: MediaSource): MediaSource = object : MediaSource {
        override fun open(): MediaStream {
            val network = upstream.open()
            val part = runCatching { begin(chapterId) }
                .onFailure { AppLog.warn("could not start the streaming cache", it) }
                .getOrNull()
            return if (part == null) network else RetainingStream(network, part, chapterId)
        }
    }

    /** Exact bytes/files represented by completed cache entries; `.part` files are not reusable. */
    fun stats(): StreamingCacheStats {
        val files = completedFiles()
        return StreamingCacheStats(bytes = files.sumOf(File::length), files = files.size)
    }

    /** Clears only rebuildable streaming bytes. Explicit downloads live in a different root. */
    @Synchronized
    fun clear(): Long {
        if (ownedDirectories.any { Files.isSymbolicLink(it.toPath()) }) {
            if (Files.isSymbolicLink(root.toPath())) runCatching { Files.deleteIfExists(root.toPath()) }
            return 0L
        }
        if (!root.isDirectory) return 0L
        var freed = 0L
        root.listFiles().orEmpty().forEach { file ->
            val path = file.toPath()
            if (Files.isSymbolicLink(path)) {
                runCatching { Files.deleteIfExists(path) }
            } else if (file.isFile) {
                val size = file.length()
                if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) freed += size
            }
        }
        runCatching { Files.deleteIfExists(root.toPath()) }
        return freed
    }

    private fun begin(chapterId: Int): File {
        check(ownedDirectories.none { Files.isSymbolicLink(it.toPath()) }) {
            "cache directory is a symlink"
        }
        ownedDirectories.forEach { Files.createDirectories(it.toPath()) }
        check(ownedDirectories.all { SecureFiles.restrictDirectoryToOwner(it.toPath()) }) {
            "cache directory is not owner-only"
        }
        val part = resolve(chapterFileName(chapterId) + DownloadStorage.PartSuffix)
        RandomAccessFile(part, "rw").use { it.setLength(0L) }
        SecureFiles.restrictToOwner(part.toPath())
        return part
    }

    private inner class RetainingStream(
        private val upstream: MediaStream,
        private val part: File,
        private val chapterId: Int,
    ) : MediaStream {
        private var output: RandomAccessFile? = runCatching { RandomAccessFile(part, "rw") }.getOrNull()
        private var written = 0L
        private var finished = false

        override val length: Long get() = upstream.length
        override val isSeekable: Boolean get() = upstream.isSeekable

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            val read = upstream.read(buffer, offset, count)
            if (read > 0) {
                val sink = output
                if (sink != null) {
                    runCatching { sink.write(buffer, offset, read) }
                        .onSuccess { written += read }
                        .onFailure { abandon("could not retain streamed audio", it) }
                }
            } else if (read < 0) {
                finish()
            }
            return read
        }

        override fun seek(position: Long): Boolean {
            if (position != written) abandon()
            return upstream.seek(position)
        }

        override fun close() {
            if (!finished) abandon()
            upstream.close()
        }

        private fun finish() {
            if (finished) return
            finished = true
            val sink = output ?: return
            output = null
            val durable = runCatching {
                sink.fd.sync()
                sink.close()
                check(length < 0L || written == length) { "stream ended at $written of $length bytes" }
                check(validator.looksDecodable(part)) { "streamed audio did not validate" }
                val target = resolve(chapterFileName(chapterId))
                runCatching {
                    Files.move(
                        part.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }.recoverCatching {
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }.getOrThrow()
                SecureFiles.restrictToOwner(target.toPath())
                prune()
            }
            durable.onFailure {
                runCatching { sink.close() }
                runCatching { Files.deleteIfExists(part.toPath()) }
                AppLog.warn("streamed audio was not cached", it)
            }
        }

        private fun abandon(message: String? = null, failure: Throwable? = null) {
            output?.let { runCatching { it.close() } }
            output = null
            runCatching { Files.deleteIfExists(part.toPath()) }
            if (message != null && failure != null) AppLog.warn(message, failure)
        }
    }

    @Synchronized
    private fun prune() {
        if (maxBytes < 0L) return
        val oldestFirst = completedFiles().sortedBy(File::lastModified).toMutableList()
        var bytes = oldestFirst.sumOf(File::length)
        while (bytes > maxBytes && oldestFirst.isNotEmpty()) {
            val victim = oldestFirst.removeFirst()
            val size = victim.length()
            if (runCatching { Files.deleteIfExists(victim.toPath()) }.getOrDefault(false)) bytes -= size
        }
    }

    private fun completedFiles(): List<File> =
        if (ownedDirectories.any { Files.isSymbolicLink(it.toPath()) }) emptyList() else root.listFiles().orEmpty().filter { file ->
            file.isFile &&
                !Files.isSymbolicLink(file.toPath()) &&
                file.name.endsWith(".mp3") &&
                FileDownloadIndexStore.isSafeName(file.name)
        }

    private fun resolve(name: String): File {
        require(FileDownloadIndexStore.isSafeName(name)) { "unsafe cache file name: $name" }
        require(ownedDirectories.none { Files.isSymbolicLink(it.toPath()) }) {
            "cache directory is a symlink"
        }
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val resolved = rootPath.resolve(name).normalize()
        require(resolved.startsWith(rootPath)) { "cache path escaped its root: $name" }
        require(!Files.isSymbolicLink(resolved)) { "cache path is a symlink: $name" }
        return resolved.toFile()
    }

    companion object {
        /** A rebuildable ceiling, not a reservation. Explicit downloads have no automatic cap. */
        const val DefaultMaxBytes: Long = 1024L * 1024 * 1024

        fun forIdentity(identity: StorageIdentity, cacheDir: File = AppDirectories.cacheDir()): StreamingCache {
            val audio = File(cacheDir, "audio")
            val server = File(audio, identity.serverKey)
            val account = File(server, identity.accountKey)
            return StreamingCache(account, ownedDirectories = listOf(audio, server, account))
        }
    }
}

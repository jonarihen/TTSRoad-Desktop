package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File
import java.nio.file.Files

/** Owner-only, bounded read-along documents for one stable server/account identity. */
class ReadAlongDiskCache(
    val root: File,
    private val maxBytes: Long = DefaultMaxBytes,
    private val maxEntries: Int = DefaultMaxEntries,
    private val ownedDirectories: List<File> = listOf(root),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(CachedReadAlong::class.java)

    fun read(chapterId: Int): CachedReadAlong? = runCatching {
        val file = fileFor(chapterId)
        if (!file.isFile) return null
        prepare()
        if (!SecureFiles.restrictToOwner(file.toPath())) return null
        val cached = adapter.fromJson(file.readText()) ?: return null
        if (cached.version != CachedReadAlong.CurrentVersion || cached.response.chapter.id != chapterId) {
            file.delete()
            return null
        }
        file.setLastModified(clock())
        cached
    }.onFailure { AppLog.warn("could not read cached read-along document", it) }.getOrNull()

    fun write(chapterId: Int, value: CachedReadAlong) {
        if (chapterId <= 0 || value.response.chapter.id != chapterId) return
        runCatching {
            prepare()
            val file = fileFor(chapterId)
            val restricted = SecureFiles.writeAtomically(
                file,
                adapter.toJson(value.copy(version = CachedReadAlong.CurrentVersion)),
            )
            if (!restricted) {
                file.delete()
                error("read-along cache file is not owner-only")
            }
            file.setLastModified(clock())
            evictToBound()
        }.onFailure { AppLog.warn("could not cache read-along document", it) }
    }

    fun delete(chapterId: Int) {
        runCatching { fileFor(chapterId).delete() }
    }

    fun clear(): Long = runCatching {
        safeFiles().sumOf { file -> file.length().also { file.delete() } }
    }.getOrDefault(0L)

    fun size(): Int = safeFiles().size
    fun bytes(): Long = safeFiles().sumOf(File::length)

    private fun evictToBound() {
        val newestFirst = safeFiles().sortedByDescending(File::lastModified).toMutableList()
        var bytes = newestFirst.sumOf(File::length)
        while (newestFirst.size > maxEntries || bytes > maxBytes) {
            val oldest = newestFirst.removeLastOrNull() ?: break
            val length = oldest.length()
            if (oldest.delete()) bytes -= length
        }
    }

    private fun prepare() {
        check(ownedDirectories.none { Files.isSymbolicLink(it.toPath()) }) {
            "read-along cache directory is a symlink"
        }
        ownedDirectories.forEach { Files.createDirectories(it.toPath()) }
        check(ownedDirectories.none { Files.isSymbolicLink(it.toPath()) }) {
            "read-along cache directory is a symlink"
        }
        check(ownedDirectories.all { SecureFiles.restrictDirectoryToOwner(it.toPath()) }) {
            "read-along cache directory is not owner-only"
        }
    }

    private fun safeFiles(): List<File> {
        if (ownedDirectories.any { Files.isSymbolicLink(it.toPath()) }) return emptyList()
        return root.listFiles { file ->
            file.isFile && !Files.isSymbolicLink(file.toPath()) && SafeFile.matches(file.name)
        }?.toList().orEmpty()
    }

    private fun fileFor(chapterId: Int): File {
        require(chapterId > 0) { "invalid chapter id" }
        require(ownedDirectories.none { Files.isSymbolicLink(it.toPath()) }) {
            "read-along cache directory is a symlink"
        }
        val base = root.toPath().toAbsolutePath().normalize()
        val resolved = base.resolve("chapter-$chapterId.json").normalize()
        require(resolved.startsWith(base)) { "read-along path escaped its root" }
        require(!Files.isSymbolicLink(resolved)) { "read-along cache file is a symlink" }
        return resolved.toFile()
    }

    companion object {
        const val DefaultMaxBytes: Long = 64L * 1024L * 1024L
        const val DefaultMaxEntries: Int = 80
        private val SafeFile = Regex("chapter-[1-9][0-9]*\\.json")

        fun forIdentity(
            identity: StorageIdentity,
            cacheDir: File = AppDirectories.cacheDir(),
        ): ReadAlongDiskCache {
            val readAlong = File(cacheDir, "readalong")
            val server = File(readAlong, identity.serverKey)
            val account = File(server, identity.accountKey)
            return ReadAlongDiskCache(account, ownedDirectories = listOf(readAlong, server, account))
        }
    }
}

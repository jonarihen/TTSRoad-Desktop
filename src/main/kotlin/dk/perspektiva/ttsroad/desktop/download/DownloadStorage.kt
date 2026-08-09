package dk.perspektiva.ttsroad.desktop.download

import dk.perspektiva.ttsroad.desktop.data.AppDirectories
import dk.perspektiva.ttsroad.desktop.data.AppLog
import dk.perspektiva.ttsroad.desktop.data.StorageIdentity
import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The directory one account's downloads live in, and the only thing allowed to turn a name into a
 * path inside it.
 *
 * Every path this app writes, reads or deletes for a download goes through [resolve]. That is the
 * point: the acceptance criterion is that cleanup "cannot escape the owned roots", and the way to
 * get that is to have exactly one function that can produce a path at all, rather than a rule that
 * each call site is expected to remember.
 *
 * [root] is `<data>/downloads/<serverKey>/<accountKey>`. The namespacing is
 * [StorageIdentity]'s — two servers with identically-named fictions cannot collide, and two
 * accounts on one machine cannot see each other's files.
 */
class DownloadStorage(
    val root: File,
) {

    /** `downloads.json` for this account, kept beside the audio it describes. */
    val indexFile: File get() = File(root, IndexFileName)

    /**
     * Creates the directory tree, owner-only.
     *
     * Owner-only matters here for the same reason it does for the session file: a download
     * directory is a reading history spelled out in file names, and a shared machine should not
     * make one user's library listing world-readable. Returns false if the restriction could not be
     * applied, which the caller may want to report rather than treat as fatal.
     */
    fun prepare(): Boolean {
        val created = runCatching { Files.createDirectories(root.toPath()) }
            .onFailure { AppLog.warn("could not create the download directory", it) }
        if (created.isFailure) return false
        // The *directory* form: a download root without its traverse bit cannot be written to at
        // all, and the resulting "Permission denied" looks like a broken download rather than a
        // permission mask this app applied to itself.
        return SecureFiles.restrictDirectoryToOwner(root.toPath())
    }

    /**
     * Turns a generated file name into a path inside [root], or throws.
     *
     * Three checks, and each catches something the others do not:
     *
     * 1. The name must be one this app would have written ([FileDownloadIndexStore.isSafeName]),
     *    which rejects separators and `..` outright.
     * 2. The *resolved* path must still be inside the root after normalisation. Belt and braces
     *    against a name that slips the first check on some platform's path semantics.
     * 3. The resolved path must not be a symlink. Without this, an attacker who can create files in
     *    the download directory — or a restored backup — could point `512.mp3` at something else
     *    and have this app truncate or delete it on the user's behalf.
     */
    fun resolve(name: String): File {
        require(FileDownloadIndexStore.isSafeName(name)) { "unsafe download file name: $name" }

        val rootPath = root.toPath().toAbsolutePath().normalize()
        val resolved = rootPath.resolve(name).normalize()
        require(resolved.startsWith(rootPath)) { "download path escaped its root: $name" }
        require(!Files.isSymbolicLink(resolved)) { "download path is a symlink: $name" }

        return resolved.toFile()
    }

    /** Deletes one download and any half-finished part beside it. Missing files are not an error. */
    fun delete(name: String) {
        for (candidate in listOf(name, "$name$PartSuffix")) {
            runCatching {
                val path = resolve(candidate).toPath()
                Files.deleteIfExists(path)
            }.onFailure { AppLog.warn("could not delete $candidate", it) }
        }
    }

    /**
     * Removes every file under [root] and the directory itself.
     *
     * Walks with [LinkOption.NOFOLLOW_LINKS] and refuses to descend into a symlinked directory, so
     * a link planted in the download tree cannot redirect a "delete all downloads" into the user's
     * home. Returns the number of bytes reclaimed.
     */
    fun deleteAll(): Long {
        if (!root.isDirectory) return 0L
        var freed = 0L
        val entries = root.listFiles().orEmpty()
        for (file in entries) {
            val path = file.toPath()
            if (Files.isSymbolicLink(path)) {
                // Delete the link, never what it points at.
                runCatching { Files.delete(path) }
                continue
            }
            if (file.isDirectory) {
                // The layout is flat: a directory here is not something this app made.
                AppLog.warn("skipping an unexpected directory in the download root: ${file.name}")
                continue
            }
            val size = file.length()
            if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) freed += size
        }
        runCatching { Files.deleteIfExists(root.toPath()) }
        return freed
    }

    /** Bytes actually occupied, measured rather than remembered — what Settings must agree with. */
    fun bytesOnDisk(): Long =
        root.listFiles().orEmpty()
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .sumOf { it.length() }

    /**
     * Whether there is room for [bytes], keeping a margin.
     *
     * The margin exists because filling a filesystem to the last byte breaks far more than this
     * app — journals, temp files, the desktop session — and a download is never worth that. A
     * server that did not report a size gives [bytes] of 0, which checks only the margin.
     */
    fun hasRoomFor(bytes: Long): Boolean {
        val usable = runCatching { root.usableSpace }.getOrDefault(0L)
        // usableSpace reports 0 for a path that does not exist yet; fall back to the nearest
        // ancestor that does rather than refusing every download on a fresh install.
        val space = if (usable > 0L) usable else nearestExistingAncestor()?.usableSpace ?: 0L
        if (space <= 0L) return true // Unknown is not the same as full; let the write fail honestly.
        return space - bytes >= FreeSpaceMarginBytes
    }

    private fun nearestExistingAncestor(): File? {
        var candidate: File? = root
        while (candidate != null && !candidate.exists()) candidate = candidate.parentFile
        return candidate
    }

    companion object {
        const val IndexFileName: String = "downloads.json"
        const val PartSuffix: String = ".part"

        /**
         * How much free space to leave untouched. 256 MB — enough that the machine keeps working,
         * small enough not to refuse downloads on a nearly-full but usable disk.
         */
        const val FreeSpaceMarginBytes: Long = 256L * 1024 * 1024

        /** The directory holding every account's downloads, under the data root. */
        fun downloadsRoot(dataDir: File = AppDirectories.dataDir()): File = File(dataDir, "downloads")

        /**
         * The storage for one signed-in session.
         *
         * Takes the identity rather than a session so that nothing here can accidentally hold a
         * token, and so a test can build one from two strings.
         */
        fun forIdentity(identity: StorageIdentity, dataDir: File = AppDirectories.dataDir()): DownloadStorage =
            DownloadStorage(File(downloadsRoot(dataDir), identity.relativePath))
    }
}

/** Thrown when a write cannot proceed because the disk is full. Mapped to [DownloadFailure.OutOfSpace]. */
class OutOfSpaceException(message: String) : IOException(message)

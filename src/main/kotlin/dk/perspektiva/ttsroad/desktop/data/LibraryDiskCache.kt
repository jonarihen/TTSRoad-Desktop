package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.desktop.security.SecureFiles
import java.io.File
import java.nio.file.Files

/** A cached API value and the time the server last supplied it. */
data class DiskCached<out T>(val value: T, val savedAtMillis: Long)

internal data class StoredLibrarySnapshot(
    val version: Int? = null,
    val savedAtMillis: Long? = null,
    val value: LibraryResponse? = null,
)

internal data class StoredChaptersSnapshot(
    val version: Int? = null,
    val savedAtMillis: Long? = null,
    val value: ChaptersResponse? = null,
)

/**
 * Owner-only, rebuildable library/chapter metadata for one server account.
 *
 * Its root uses the exact [StorageIdentity] as audio, so two accounts or deployments can never
 * see each other's rows. There is intentionally no global catalogue mapping hashes back to titles:
 * while signed out the app cannot even enumerate another account's cached fiction names.
 */
class LibraryDiskCache(
    val root: File,
    private val ownedDirectories: List<File> = listOf(root),
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val libraryAdapter = moshi.adapter(StoredLibrarySnapshot::class.java)
    private val chaptersAdapter = moshi.adapter(StoredChaptersSnapshot::class.java)

    fun loadLibrary(): DiskCached<LibraryResponse>? = runCatching {
        val file = safeFile(LibraryFileName)
        if (!file.isFile) return null
        val stored = libraryAdapter.fromJson(file.readText()) ?: return null
        val value = stored.value ?: return null
        DiskCached(value, stored.savedAtMillis?.coerceAtLeast(0L) ?: 0L)
    }.onFailure { AppLog.warn("could not read cached library metadata", it) }.getOrNull()

    fun storeLibrary(value: LibraryResponse, savedAtMillis: Long) {
        write(
            safeFile(LibraryFileName),
            libraryAdapter.toJson(StoredLibrarySnapshot(CurrentVersion, savedAtMillis, value.forDisk())),
        )
    }

    fun loadChapters(fictionId: Int): DiskCached<ChaptersResponse>? = runCatching {
        val file = chapterFile(fictionId)
        if (!file.isFile) return null
        val stored = chaptersAdapter.fromJson(file.readText()) ?: return null
        val value = stored.value ?: return null
        // A copied/renamed file cannot cause one fiction route to render another fiction's rows.
        if (value.fiction.id != fictionId) return null
        DiskCached(value, stored.savedAtMillis?.coerceAtLeast(0L) ?: 0L)
    }.onFailure { AppLog.warn("could not read cached chapter metadata", it) }.getOrNull()

    fun storeChapters(fictionId: Int, value: ChaptersResponse, savedAtMillis: Long) {
        if (fictionId <= 0 || value.fiction.id != fictionId) return
        write(
            chapterFile(fictionId),
            chaptersAdapter.toJson(StoredChaptersSnapshot(CurrentVersion, savedAtMillis, value.forDisk())),
        )
    }

    private fun write(file: File, json: String) {
        runCatching {
            prepare()
            SecureFiles.writeAtomically(file, json)
        }.onFailure { AppLog.warn("could not write cached library metadata", it) }
    }

    private fun prepare() {
        check(ownedDirectories.none { Files.isSymbolicLink(it.toPath()) }) {
            "metadata directory is a symlink"
        }
        ownedDirectories.forEach { Files.createDirectories(it.toPath()) }
        check(ownedDirectories.none { Files.isSymbolicLink(it.toPath()) }) { "metadata root is a symlink" }
        check(ownedDirectories.all { SecureFiles.restrictDirectoryToOwner(it.toPath()) }) {
            "metadata root is not owner-only"
        }
    }

    private fun chapterFile(fictionId: Int): File {
        require(fictionId > 0) { "invalid fiction id" }
        return safeFile("chapters-$fictionId.json")
    }

    private fun safeFile(name: String): File {
        require(SafeName.matches(name)) { "unsafe metadata file name" }
        require(ownedDirectories.none { Files.isSymbolicLink(it.toPath()) }) {
            "metadata directory is a symlink"
        }
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val resolved = rootPath.resolve(name).normalize()
        require(resolved.startsWith(rootPath)) { "metadata path escaped its root" }
        require(!Files.isSymbolicLink(resolved)) { "metadata file is a symlink" }
        return resolved.toFile()
    }

    /**
     * Server-local filenames/paths and backend error detail are not needed to browse or play and
     * may contain private filesystem names. The audio URL is retained because offline playback
     * still needs a queue item; the media-source layer consults local bytes before touching it.
     */
    private fun ChapterSummary.forDisk(): ChapterSummary = copy(
        errorMessage = null,
        audio = audio?.copy(filename = null, path = null),
    )

    private fun LibraryResponse.forDisk(): LibraryResponse = copy(
        continueListening = continueListening.map { it.forDisk() },
        recentChapters = recentChapters.map { it.forDisk() },
    )

    private fun ChaptersResponse.forDisk(): ChaptersResponse = copy(
        chapters = chapters.map { it.forDisk() },
    )

    companion object {
        const val CurrentVersion: Int = 1
        const val LibraryFileName: String = "library.json"
        private val SafeName = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

        fun forIdentity(identity: StorageIdentity, cacheDir: File = AppDirectories.cacheDir()): LibraryDiskCache {
            val metadata = File(cacheDir, "metadata")
            val server = File(metadata, identity.serverKey)
            val account = File(server, identity.accountKey)
            return LibraryDiskCache(account, listOf(metadata, server, account))
        }
    }
}

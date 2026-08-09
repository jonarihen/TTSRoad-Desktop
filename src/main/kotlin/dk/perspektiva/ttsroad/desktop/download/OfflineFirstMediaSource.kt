package dk.perspektiva.ttsroad.desktop.download

import dk.perspektiva.ttsroad.desktop.data.AppLog
import dk.perspektiva.ttsroad.desktop.player.FileMediaSource
import dk.perspektiva.ttsroad.desktop.player.MediaSource
import dk.perspektiva.ttsroad.desktop.player.MediaSourceFactory

/**
 * Plays a downloaded chapter from disk, and anything else from the network.
 *
 * This is the whole of "downloaded chapters play offline": the engine is unchanged, the controller
 * is unchanged, and the decision is one lookup at the moment a chapter is prepared. Deciding here
 * rather than in the controller is deliberate — the controller should not know that downloads
 * exist, and the acceptance criterion is about which *bytes* are used, not about queue behaviour.
 *
 * The suppliers are functions rather than values because the download storage belongs to the
 * signed-in account, and a sign-out followed by a different sign-in replaces it underneath a
 * long-lived controller.
 */
class OfflineFirstMediaSourceFactory(
    private val index: () -> DownloadIndexStore?,
    private val storage: () -> DownloadStorage?,
    private val network: MediaSourceFactory,
) : MediaSourceFactory {

    override fun create(chapterId: Int, url: String): MediaSource {
        val local = localSourceFor(chapterId)
        return local ?: network.create(chapterId, url)
    }

    /**
     * The on-disk source for [chapterId], or null to fall through to the network.
     *
     * The index and the filesystem are two sources of truth and only one of them is real. A row can
     * say Downloaded while the bytes are gone — a cache cleaner, a manual delete, a restored backup
     * — and playing that row means handing the engine a missing file. So the file is checked, and a
     * row that turns out to be wrong is *corrected* rather than merely skipped: otherwise the UI
     * keeps offering "Offline" for a chapter that silently streams every time.
     */
    private fun localSourceFor(chapterId: Int): MediaSource? {
        val index = index() ?: return null
        val storage = storage() ?: return null
        val entry = DownloadIndex.find(index.entries.value, chapterId)?.takeIf { it.isOffline } ?: return null

        val file = runCatching { storage.resolve(entry.fileName) }
            .onFailure { AppLog.warn("a download entry named an unusable file", it) }
            .getOrNull()

        if (file == null || !file.isFile || file.length() <= 0L) {
            AppLog.warn("download ${entry.fileName} is missing; streaming this chapter instead")
            index.remove(chapterId)
            return null
        }
        return FileMediaSource(file)
    }
}

package dk.perspektiva.ttsroad.desktop.download

import dk.perspektiva.ttsroad.desktop.FakeRepository
import dk.perspektiva.ttsroad.desktop.data.InMemorySessionStore
import dk.perspektiva.ttsroad.desktop.data.SessionState
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * That downloads follow the signed-in account.
 *
 * The acceptance criteria here are the ones about two servers not sharing content, and about
 * signing out keeping downloads while making them inaccessible until that account returns.
 */
class DownloadCoordinatorTest {

    @TempDir
    lateinit var tempDir: File

    private fun coordinator(sessionStore: InMemorySessionStore) = DownloadCoordinator(
        sessionStore = sessionStore,
        client = OkHttpClient(),
        repository = FakeRepository(),
        dataDir = tempDir,
        cacheDir = File(tempDir, "cache"),
    )

    private fun signedIn(server: String = "https://host.example/", user: String = "alice") =
        SessionState(serverUrl = server, token = "t", username = user)

    @Test
    fun `nobody signed in means no download stack`() {
        val coordinator = coordinator(InMemorySessionStore())
        assertNull(coordinator.refresh())
        assertNull(coordinator.indexOrNull())
        assertNull(coordinator.storageOrNull())
        coordinator.close()
    }

    @Test
    fun `the same account resolves to the same stack rather than rebuilding it`() {
        val store = InMemorySessionStore(signedIn())
        val coordinator = coordinator(store)

        val first = coordinator.refresh()
        val second = coordinator.refresh()

        assertTrue(first === second, "the stack was rebuilt for an unchanged account")
        coordinator.close()
    }

    @Test
    fun `two accounts on one machine get separate directories`() {
        val store = InMemorySessionStore(signedIn(user = "alice"))
        val coordinator = coordinator(store)

        val alice = coordinator.refresh()!!.storage.root
        store.save(signedIn(user = "bob"))
        val bob = coordinator.refresh()!!.storage.root

        assertNotEquals(alice.path, bob.path)
        coordinator.close()
    }

    @Test
    fun `two servers get separate directories`() {
        val store = InMemorySessionStore(signedIn(server = "https://one.example/"))
        val coordinator = coordinator(store)

        val one = coordinator.refresh()!!.storage.root
        store.save(signedIn(server = "https://two.example/"))
        val two = coordinator.refresh()!!.storage.root

        assertNotEquals(one.path, two.path)
        coordinator.close()
    }

    @Test
    fun `signing out keeps the files and signing back in finds them`() {
        // The issue is explicit: a sign-out must not delete downloads, and the account's own
        // downloads become reachable again when it returns.
        val store = InMemorySessionStore(signedIn())
        val coordinator = coordinator(store)

        val session = coordinator.refresh()!!
        session.storage.resolve("1.mp3").writeBytes(ByteArray(32))
        val root = session.storage.root

        store.clearToken()
        // `clearToken` keeps login hints, but those are not authority to open account-protected
        // metadata. The live stack goes away while its bytes stay on disk.
        assertTrue(root.resolve("1.mp3").isFile, "signing out deleted a download")
        assertNull(coordinator.refresh(), "signed-out hints exposed the account's download index")

        store.save(signedIn())
        assertEquals(root.path, coordinator.refresh()!!.storage.root.path)
        assertTrue(coordinator.refresh()!!.storage.resolve("1.mp3").isFile)
        coordinator.close()
    }

    @Test
    fun `an advertised base url moves the namespace off the connect address`() {
        // Two connect addresses for one server must converge once discovery reports its identity,
        // or moving from a LAN address to a public hostname re-downloads the whole library.
        val lan = InMemorySessionStore(signedIn(server = "http://192.168.1.10:8000/"))
        val coordinator = coordinator(lan)
        coordinator.advertisedBaseUrl = "https://ttsroad.example"
        val viaLan = coordinator.refresh()!!.storage.root

        val public = InMemorySessionStore(signedIn(server = "https://ttsroad.example/"))
        val other = coordinator(public)
        other.advertisedBaseUrl = "https://ttsroad.example"
        val viaPublic = other.refresh()!!.storage.root

        assertEquals(viaLan.path, viaPublic.path)
        coordinator.close()
        other.close()
    }

    @Test
    fun `the index lives beside the audio it describes`() {
        val coordinator = coordinator(InMemorySessionStore(signedIn()))
        val session = coordinator.refresh()!!

        assertEquals(session.storage.root, session.indexFileParent())
        coordinator.close()
    }

    @Test
    fun `settings totals measure files by fiction and keep cache cleanup separate`() = runBlocking {
        val coordinator = coordinator(InMemorySessionStore(signedIn()))
        val session = coordinator.refresh()!!
        session.storage.resolve("1.mp3").writeBytes(ByteArray(100))
        session.storage.resolve("2.mp3").writeBytes(ByteArray(200))
        session.index.put(
            DownloadEntry(
                chapterId = 1,
                fictionId = 7,
                fictionTitle = "A Serial",
                chapterTitle = "One",
                state = DownloadState.Downloaded,
                bytesDownloaded = 100,
                fileName = "1.mp3",
            ),
        )
        session.index.put(
            DownloadEntry(
                chapterId = 2,
                fictionId = 7,
                fictionTitle = "A Serial",
                chapterTitle = "Two",
                state = DownloadState.Downloaded,
                bytesDownloaded = 200,
                fileName = "2.mp3",
            ),
        )
        val cached = File(session.streamingCache.root, "9.mp3")
        cached.parentFile.mkdirs()
        cached.writeBytes(ByteArray(1024).also { bytes ->
            bytes[0] = 'I'.code.toByte()
            bytes[1] = 'D'.code.toByte()
            bytes[2] = '3'.code.toByte()
        })

        val measured = coordinator.summary()
        assertEquals(300L, measured.downloadBytes)
        assertEquals(2, measured.downloadedChapters)
        assertEquals(OfflineFictionUsage(7, "A Serial", 300, 2), measured.fictions.single())
        assertEquals(1024L, measured.streamingCacheBytes)

        coordinator.deleteAllDownloads()
        assertEquals(0L, coordinator.summary().downloadBytes)
        assertEquals(1024L, coordinator.summary().streamingCacheBytes, "deleting downloads cleared cache too")

        coordinator.clearStreamingCache()
        assertEquals(0L, coordinator.summary().streamingCacheBytes)
        coordinator.close()
    }

    private fun DownloadSession.indexFileParent(): File = storage.indexFile.parentFile
}

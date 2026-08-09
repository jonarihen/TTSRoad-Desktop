package dk.perspektiva.ttsroad.desktop.data

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Local listening history: what "last heard" picks, how the list is bounded, and what a dismissal
 * actually applies to.
 */
class PlaybackHistoryTest {

    /** The signed-in account these snapshots belong to. */
    private val Owner = PlaybackHistory.ownerKeyFor("https://host.example", "alice")

    private val OtherOwner = PlaybackHistory.ownerKeyFor("https://host.example", "bob")

    private fun snapshot(
        fictionId: Int = 1,
        chapterId: Int = 1,
        positionSeconds: Double = 60.0,
        durationSeconds: Double = 600.0,
        recordedAtMs: Long = 1_000L,
        dismissed: Boolean = false,
        ownerKey: String = Owner,
    ) = PlaybackSnapshot(
        fictionId = fictionId,
        chapterId = chapterId,
        fictionTitle = "Fiction $fictionId",
        chapterTitle = "Chapter $chapterId",
        positionSeconds = positionSeconds,
        durationSeconds = durationSeconds,
        recordedAtMs = recordedAtMs,
        dismissed = dismissed,
        ownerKey = ownerKey,
    )

    // --- Thinning -------------------------------------------------------------------------------

    @Test
    fun `listening to one chapter leaves one record, at the furthest point reached`() {
        var history = PlaybackHistory.record(emptyList(), snapshot(positionSeconds = 10.0, recordedAtMs = 1))
        history = PlaybackHistory.record(history, snapshot(positionSeconds = 300.0, recordedAtMs = 2))

        assertEquals(1, history.size)
        assertEquals(300.0, history.single().positionSeconds)
    }

    @Test
    fun `the list is capped and drops the oldest`() {
        var history = emptyList<PlaybackSnapshot>()
        repeat(PlaybackHistory.MaxEntries + 20) { index ->
            history = PlaybackHistory.record(
                history,
                snapshot(fictionId = index, chapterId = index, recordedAtMs = index.toLong()),
            )
        }

        assertEquals(PlaybackHistory.MaxEntries, history.size)
        // Newest first, and the oldest are the ones gone.
        assertEquals((PlaybackHistory.MaxEntries + 19).toLong(), history.first().recordedAtMs)
        assertTrue(history.none { it.recordedAtMs < 20 })
    }

    @Test
    fun `records are newest first`() {
        var history = PlaybackHistory.record(emptyList(), snapshot(chapterId = 1, recordedAtMs = 100))
        history = PlaybackHistory.record(history, snapshot(chapterId = 2, recordedAtMs = 50))
        assertEquals(listOf(1, 2), history.map { it.chapterId })
    }

    // --- Dismissal ------------------------------------------------------------------------------

    @Test
    fun `a dismissal survives the next progress save for the same chapter`() {
        // Without this the 10-second progress tick would undo a dismissal the moment it was made.
        var history = PlaybackHistory.record(emptyList(), snapshot(chapterId = 7, recordedAtMs = 1))
        history = PlaybackHistory.dismiss(history, "$Owner:1:7")
        history = PlaybackHistory.record(history, snapshot(chapterId = 7, positionSeconds = 120.0, recordedAtMs = 2))

        assertTrue(history.single().dismissed)
        assertNull(PlaybackHistory.lastHeard(history, Owner))
    }

    @Test
    fun `dismissal applies to the snapshot, so a different chapter still appears`() {
        // The requirement, stated as a test: this is not "hide for today".
        var history = PlaybackHistory.record(emptyList(), snapshot(chapterId = 7, recordedAtMs = 1))
        history = PlaybackHistory.dismiss(history, "$Owner:1:7")
        history = PlaybackHistory.record(history, snapshot(chapterId = 8, recordedAtMs = 2))

        val lastHeard = PlaybackHistory.lastHeard(history, Owner)
        assertEquals(8, lastHeard?.chapterId)
    }

    @Test
    fun `dismissing one chapter leaves the others alone`() {
        var history = PlaybackHistory.record(emptyList(), snapshot(chapterId = 1, recordedAtMs = 1))
        history = PlaybackHistory.record(history, snapshot(chapterId = 2, recordedAtMs = 2))
        history = PlaybackHistory.dismiss(history, "$Owner:1:1")

        assertTrue(history.first { it.chapterId == 1 }.dismissed)
        assertFalse(history.first { it.chapterId == 2 }.dismissed)
    }

    // --- Last heard and jump-back ---------------------------------------------------------------

    @Test
    fun `last heard is the most recent resumable snapshot`() {
        var history = PlaybackHistory.record(emptyList(), snapshot(chapterId = 1, recordedAtMs = 10))
        history = PlaybackHistory.record(history, snapshot(chapterId = 2, recordedAtMs = 20))
        assertEquals(2, PlaybackHistory.lastHeard(history, Owner)?.chapterId)
    }

    @Test
    fun `a finished chapter is not offered as somewhere to continue`() {
        // At 96% the controller has already marked it played, so "continue" would mean "replay the
        // last few seconds and auto-advance".
        val finished = snapshot(positionSeconds = 599.0, durationSeconds = 600.0)
        assertNull(PlaybackHistory.lastHeard(listOf(finished), Owner))
    }

    @Test
    fun `a chapter with an unknown duration is still offered`() {
        // Progress reads as zero rather than as finished, which is the safe way round.
        val unknown = snapshot(positionSeconds = 30.0, durationSeconds = 0.0)
        assertEquals(unknown.chapterId, PlaybackHistory.lastHeard(listOf(unknown), Owner)?.chapterId)
    }

    @Test
    fun `last heard is null when everything is dismissed`() {
        val history = listOf(snapshot(dismissed = true))
        assertNull(PlaybackHistory.lastHeard(history, Owner))
    }

    @Test
    fun `jump-back offers one entry per fiction, newest first`() {
        var history = emptyList<PlaybackSnapshot>()
        // Two chapters of one serial, then one of another. The serial must appear once.
        history = PlaybackHistory.record(history, snapshot(fictionId = 1, chapterId = 1, recordedAtMs = 1))
        history = PlaybackHistory.record(history, snapshot(fictionId = 1, chapterId = 2, recordedAtMs = 2))
        history = PlaybackHistory.record(history, snapshot(fictionId = 2, chapterId = 9, recordedAtMs = 3))

        val choices = PlaybackHistory.jumpBackChoices(history, Owner)
        assertEquals(listOf(2, 1), choices.map { it.fictionId })
        assertEquals(2, choices.first { it.fictionId == 1 }.chapterId)
    }

    @Test
    fun `jump-back is limited and skips dismissed entries`() {
        var history = emptyList<PlaybackSnapshot>()
        repeat(10) { index ->
            history = PlaybackHistory.record(
                history,
                snapshot(fictionId = index, chapterId = index, recordedAtMs = index.toLong()),
            )
        }
        history = PlaybackHistory.dismiss(history, "$Owner:9:9")

        val choices = PlaybackHistory.jumpBackChoices(history, Owner, limit = 3)
        assertEquals(3, choices.size)
        assertTrue(choices.none { it.fictionId == 9 })
    }

    @Test
    fun `progress is bounded to zero and one`() {
        assertEquals(0f, snapshot(positionSeconds = -5.0).progress)
        assertEquals(1f, snapshot(positionSeconds = 9_999.0, durationSeconds = 600.0).progress)
        assertEquals(0f, snapshot(durationSeconds = 0.0).progress)
    }

    // --- The file store -------------------------------------------------------------------------

    @Test
    fun `history survives a restart`(@TempDir dir: File) {
        val file = dir.resolve("history.json")
        FilePlaybackHistoryStore(file).record(snapshot(chapterId = 4, recordedAtMs = 99))

        val reloaded = FilePlaybackHistoryStore(file).history.value
        assertEquals(1, reloaded.size)
        assertEquals(4, reloaded.single().chapterId)
    }

    @Test
    fun `a dismissal survives a restart`(@TempDir dir: File) {
        val file = dir.resolve("history.json")
        val store = FilePlaybackHistoryStore(file)
        store.record(snapshot(chapterId = 4))
        store.dismiss("$Owner:1:4")

        assertTrue(FilePlaybackHistoryStore(file).history.value.single().dismissed)
    }

    @Test
    fun `an oversized file from another build is trimmed on the way in`(@TempDir dir: File) {
        val file = dir.resolve("history.json")
        val store = FilePlaybackHistoryStore(file)
        repeat(PlaybackHistory.MaxEntries + 30) { index ->
            store.record(snapshot(fictionId = index, chapterId = index, recordedAtMs = index.toLong()))
        }
        assertEquals(PlaybackHistory.MaxEntries, FilePlaybackHistoryStore(file).history.value.size)
    }

    @Test
    fun `a corrupt file degrades to an empty history`(@TempDir dir: File) {
        val file = dir.resolve("history.json")
        file.writeText("not json at all")
        assertTrue(FilePlaybackHistoryStore(file).history.value.isEmpty())
    }

    @Test
    fun `the history file holds no server address and no credential`(@TempDir dir: File) {
        // The type has nowhere to put one, which is the real guarantee; this asserts the shape of
        // what actually lands on disk.
        val file = dir.resolve("history.json")
        FilePlaybackHistoryStore(file).record(snapshot())
        val text = file.readText()
        assertFalse(text.contains("http", ignoreCase = true))
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("bearer", ignoreCase = true))
    }

    @Test
    fun `clearing empties the file`(@TempDir dir: File) {
        val file = dir.resolve("history.json")
        val store = FilePlaybackHistoryStore(file)
        store.record(snapshot())
        store.clear()
        assertTrue(store.history.value.isEmpty())
        assertTrue(FilePlaybackHistoryStore(file).history.value.isEmpty())
    }

    // --- Account scoping ------------------------------------------------------------------------

    @Test
    fun `one account never sees another's reading history`() {
        // The file is machine-local and outlives every session, but its contents are not: signing
        // out and signing in as somebody else on the same desktop must not expose what the previous
        // person was reading.
        var history = PlaybackHistory.record(emptyList(), snapshot(fictionId = 1, ownerKey = Owner))
        history = PlaybackHistory.record(history, snapshot(fictionId = 2, ownerKey = OtherOwner))

        assertEquals(listOf(1), PlaybackHistory.jumpBackChoices(history, Owner).map { it.fictionId })
        assertEquals(listOf(2), PlaybackHistory.jumpBackChoices(history, OtherOwner).map { it.fictionId })
        assertEquals(1, PlaybackHistory.lastHeard(history, Owner)?.fictionId)
        assertEquals(2, PlaybackHistory.lastHeard(history, OtherOwner)?.fictionId)
    }

    @Test
    fun `a snapshot from an older build belongs to nobody`() {
        // Losing the strip once is a much better outcome than showing one account's titles to the
        // next person who signs in.
        val history = PlaybackHistory.record(emptyList(), snapshot(ownerKey = ""))

        assertTrue(PlaybackHistory.jumpBackChoices(history, Owner).isEmpty())
        assertNull(PlaybackHistory.lastHeard(history, Owner))
        // And a blank *current* owner — a signed-out screen — sees nothing either.
        assertTrue(PlaybackHistory.jumpBackChoices(history, "").isEmpty())
    }

    @Test
    fun `two accounts holding the same fiction id keep separate rows and dismissals`() {
        // Fiction ids are unique only within a server, so the identity has to carry the owner.
        var history = PlaybackHistory.record(emptyList(), snapshot(fictionId = 5, chapterId = 5, ownerKey = Owner))
        history = PlaybackHistory.record(history, snapshot(fictionId = 5, chapterId = 5, ownerKey = OtherOwner))
        assertEquals(2, history.size, "one account's snapshot replaced the other's")

        history = PlaybackHistory.dismiss(history, "$Owner:5:5")
        assertEquals(1, PlaybackHistory.jumpBackChoices(history, OtherOwner).size)
        assertTrue(PlaybackHistory.jumpBackChoices(history, Owner).isEmpty())
    }

    @Test
    fun `the owner key names neither the server nor the account`() {
        val key = PlaybackHistory.ownerKeyFor("https://host.example", "alice")
        assertFalse(key.contains("host.example"))
        assertFalse(key.contains("alice"))
        assertTrue(key.all { it in "0123456789abcdef" })
        assertFalse(key == PlaybackHistory.ownerKeyFor("https://host.example", "bob"))
    }

    @Test
    fun `scheme and host are case-insensitive but the path is not`() {
        val canonical = PlaybackHistory.ownerKeyFor("https://host.example", "alice")
        // Scheme, host and a trailing slash are noise.
        assertEquals(canonical, PlaybackHistory.ownerKeyFor("HTTPS://Host.Example/", "alice"))

        // The path is not. Retrofit supports path-based base URLs and normalizeBaseUrl keeps
        // whatever the user configured, so these are two deployments — and folding them together
        // would let two people with the same username read and dismiss each other's history.
        assertFalse(
            PlaybackHistory.ownerKeyFor("https://host.example/TTSRoad/", "alice") ==
                PlaybackHistory.ownerKeyFor("https://host.example/ttsroad/", "alice"),
        )
        // A path still identifies one deployment consistently.
        assertEquals(
            PlaybackHistory.ownerKeyFor("https://host.example/TTSRoad/", "alice"),
            PlaybackHistory.ownerKeyFor("https://HOST.example/TTSRoad", "alice"),
        )
    }

    @Test
    fun `usernames differing only in case are different accounts`() {
        // The stored username is what the server called the account, so it is already canonical;
        // folding it would merge two real accounts on a case-sensitive server. Splitting one
        // identity costs a lost strip, merging two discloses one person's reading to another.
        assertFalse(
            PlaybackHistory.ownerKeyFor("https://host.example", "Alice") ==
                PlaybackHistory.ownerKeyFor("https://host.example", "alice"),
        )
    }
}

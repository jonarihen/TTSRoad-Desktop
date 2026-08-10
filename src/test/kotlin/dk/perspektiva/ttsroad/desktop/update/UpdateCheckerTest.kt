package dk.perspektiva.ttsroad.desktop.update

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Throttling, dismissal and failure handling around the update check.
 *
 * Everything here runs without a network: the release feed is a lambda and the clock is a variable,
 * which is the point of the [ReleaseSource] seam.
 */
class UpdateCheckerTest {

    private val installed = "1.0.1"

    /** A realistic wall-clock value: an install that has never checked is due immediately. */
    private val now = 1_700_000_000_000L

    private fun release(
        version: String,
        assets: List<ReleaseAsset> = listOf(
            ReleaseAsset("ttsroad_$version-1_amd64.deb", "https://example.invalid/deb", 100),
        ),
    ) = LatestRelease(
        tag = "v$version",
        version = version,
        notes = "Notes for $version",
        htmlUrl = "https://example.invalid/releases/v$version",
        assets = assets,
    )

    private fun checker(
        store: UpdateSettingsStore,
        now: () -> Long,
        source: ReleaseSource,
        osName: String = "Linux",
        architecture: String = "amd64",
    ) = UpdateChecker(
        source = source,
        settingsStore = store,
        installedVersion = installed,
        osName = osName,
        architecture = architecture,
        clock = now,
    )

    // --- Finding an update ----------------------------------------------------------------------

    @Test
    fun `a newer release is reported with the asset for this machine`() = runTest {
        val store = InMemoryUpdateSettingsStore()
        val status = checker(store, { now }, { release("1.0.2") }).check(manual = true)

        val available = assertIs<UpdateStatus.Available>(status)
        assertEquals("1.0.2", available.release.version)
        assertEquals("ttsroad_1.0.2-1_amd64.deb", available.asset?.name)
    }

    @Test
    fun `a release with nothing for this architecture is still announced, without a download`() =
        runTest {
            val store = InMemoryUpdateSettingsStore()
            val status = checker(store, { now }, { release("1.0.2") }, architecture = "aarch64")
                .check(manual = true)

            val available = assertIs<UpdateStatus.Available>(status)
            // Told that a version exists, but not handed a package dpkg would refuse.
            assertNull(available.asset)
        }

    @Test
    fun `the same version is up to date, not an update`() = runTest {
        val store = InMemoryUpdateSettingsStore()
        val status = checker(store, { now }, { release(installed) }).check(manual = true)
        assertIs<UpdateStatus.UpToDate>(status)
    }

    @Test
    fun `a project with no published release is up to date, not a failure`() = runTest {
        val store = InMemoryUpdateSettingsStore()
        val status = checker(store, { now }, { null }).check(manual = true)
        assertIs<UpdateStatus.UpToDate>(status)
    }

    // --- Throttling -----------------------------------------------------------------------------

    @Test
    fun `an automatic check runs once per launch and no more`() = runTest {
        val store = InMemoryUpdateSettingsStore()
        var calls = 0
        val subject = checker(store, { now }, { calls++; release("1.0.2") })

        assertIs<UpdateStatus.Available>(subject.check(manual = false))
        assertIs<UpdateStatus.Unknown>(subject.check(manual = false))
        assertEquals(1, calls)
    }

    @Test
    fun `an automatic check waits a day after the last one`() = runTest {
        val store = InMemoryUpdateSettingsStore(UpdateSettings(lastCheckMillis = 1_000L))
        var calls = 0
        val justUnderADay = 1_000L + UpdateCheckIntervalMillis - 1

        assertIs<UpdateStatus.Unknown>(
            checker(store, { justUnderADay }, { calls++; release("1.0.2") }).check(manual = false),
        )
        assertEquals(0, calls)

        assertIs<UpdateStatus.Available>(
            checker(store, { justUnderADay + 1 }, { calls++; release("1.0.2") })
                .check(manual = false),
        )
        assertEquals(1, calls)
    }

    @Test
    fun `a manual check ignores both the daily interval and the once-per-launch flag`() = runTest {
        val store = InMemoryUpdateSettingsStore(UpdateSettings(lastCheckMillis = 1_000L))
        var calls = 0
        val subject = checker(store, { 1_001L }, { calls++; release("1.0.2") })

        assertIs<UpdateStatus.Available>(subject.check(manual = true))
        assertIs<UpdateStatus.Available>(subject.check(manual = true))
        assertEquals(2, calls)
    }

    @Test
    fun `a clock that moved backwards does not park the next check in the future`() = runTest {
        val store = InMemoryUpdateSettingsStore(UpdateSettings(lastCheckMillis = 10_000L))
        var calls = 0

        assertIs<UpdateStatus.Available>(
            checker(store, { 5_000L }, { calls++; release("1.0.2") }).check(manual = false),
        )
        assertEquals(1, calls)
    }

    @Test
    fun `turning automatic checks off stops them, and a manual check still works`() = runTest {
        val store = InMemoryUpdateSettingsStore()
        var calls = 0
        val subject = checker(store, { now }, { calls++; release("1.0.2") })
        subject.setAutomatic(false)

        assertIs<UpdateStatus.Unknown>(subject.check(manual = false))
        assertEquals(0, calls)
        assertIs<UpdateStatus.Available>(subject.check(manual = true))
        assertEquals(1, calls)
    }

    @Test
    fun `a successful check records when it happened`() = runTest {
        val store = InMemoryUpdateSettingsStore()
        checker(store, { 4_242L }, { release(installed) }).check(manual = true)
        assertEquals(4_242L, store.settings.value.lastCheckMillis)
    }

    // --- Dismissal ------------------------------------------------------------------------------

    @Test
    fun `a dismissed version stops being announced automatically`() = runTest {
        val store = InMemoryUpdateSettingsStore()
        checker(store, { now }, { release("1.0.2") }).dismiss("1.0.2")

        val status = checker(store, { now }, { release("1.0.2") }).check(manual = false)
        assertIs<UpdateStatus.UpToDate>(status)
    }

    @Test
    fun `a version newer than the dismissed one is announced again`() = runTest {
        val store = InMemoryUpdateSettingsStore(UpdateSettings(dismissedVersion = "1.0.2"))
        val status = checker(store, { now }, { release("1.0.3") }).check(manual = false)
        assertIs<UpdateStatus.Available>(status)
    }

    @Test
    fun `a manual check shows a dismissed version, because the user just asked`() = runTest {
        val store = InMemoryUpdateSettingsStore(UpdateSettings(dismissedVersion = "1.0.2"))
        val status = checker(store, { now }, { release("1.0.2") }).check(manual = true)
        assertIs<UpdateStatus.Available>(status)
    }

    // --- Failure --------------------------------------------------------------------------------

    @Test
    fun `a network failure is reported without leaking the response`() = runTest {
        val store = InMemoryUpdateSettingsStore()
        val status = checker(store, { now }, { throw IOException("connect timed out to 10.0.0.1") })
            .check(manual = true)

        val failed = assertIs<UpdateStatus.Failed>(status)
        assertFalse(failed.reason.contains("10.0.0.1"))
    }

    @Test
    fun `a failed check does not count as a check`() = runTest {
        // Otherwise one outage would silence update checking for a whole day.
        val store = InMemoryUpdateSettingsStore()
        checker(store, { 9_000L }, { throw IOException("offline") }).check(manual = true)
        assertEquals(0L, store.settings.value.lastCheckMillis)
    }

    // --- Persisted settings ---------------------------------------------------------------------

    @Test
    fun `a stored file from another build loads degraded rather than throwing`() {
        val settings = StoredUpdateSettings(automatic = null, lastCheckMillis = null).toSettings()
        assertTrue(settings.automatic)
        assertEquals(0L, settings.lastCheckMillis)
        assertNull(settings.dismissedVersion)
    }

    @Test
    fun `a negative stored timestamp is treated as never checked`() {
        assertEquals(0L, StoredUpdateSettings(lastCheckMillis = -1L).toSettings().lastCheckMillis)
    }
}

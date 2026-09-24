package dk.perspektiva.ttsroad.desktop.update

import dk.perspektiva.ttsroad.desktop.ui.UpdateStateHolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Throttling, dismissal and failure handling around the update check.
 *
 * Everything here runs without a network: the release feed is a lambda and the clock is a variable,
 * which is the point of the [ReleaseSource] seam.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckerTest {

    @TempDir
    lateinit var directory: File

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
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = UpdateChecker(
        source = source,
        settingsStore = store,
        installedVersion = installed,
        osName = osName,
        architecture = architecture,
        clock = now,
        ioDispatcher = ioDispatcher,
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

    @Test
    fun `cancellation from the release source propagates unchanged`() = runTest {
        val store = InMemoryUpdateSettingsStore()
        val cancelled = CancellationException("cancelled check")
        val subject = checker(store, { now }, { throw cancelled })

        assertEquals("cancelled check", assertFailsWith<CancellationException> { subject.check(manual = true) }.message)
        assertEquals(0L, store.settings.value.lastCheckMillis)
    }

    @Test
    fun `a source returning after cancellation cannot record or publish a release`() = runTest {
        val store = InMemoryUpdateSettingsStore()
        var published = false
        val subject = checker(store, { now }, {
            currentCoroutineContext().cancel()
            release("1.0.2")
        })

        val job = launch {
            subject.check(manual = true)
            published = true
        }
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(published)
        assertEquals(0L, store.settings.value.lastCheckMillis)
    }

    @Test
    fun `a network failure after cancellation is not reported as an update failure`() = runTest {
        val store = InMemoryUpdateSettingsStore()
        val subject = checker(store, { now }, {
            currentCoroutineContext().cancel()
            throw IOException("cancelled socket")
        })
        var published = false

        val job = launch {
            subject.check(manual = true)
            published = true
        }
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(published)
        assertEquals(0L, store.settings.value.lastCheckMillis)
    }

    @Test
    fun `a newer manual check wins over a cancelled automatic result`() = runTest {
        assertStaleCheckIgnored(fail = false)
    }

    @Test
    fun `a cancelled automatic failure cannot replace a newer manual result`() = runTest {
        assertStaleCheckIgnored(fail = true)
    }

    private suspend fun TestScope.assertStaleCheckIgnored(fail: Boolean) {
        val store = InMemoryUpdateSettingsStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val releaseFirst = CompletableDeferred<Unit>()
        var calls = 0
        val subject = checker(store, { now }, {
            if (++calls == 1) {
                withContext(NonCancellable) { releaseFirst.await() }
                if (fail) throw IOException("stale failure")
                release("1.0.2")
            } else {
                release("1.0.3")
            }
        }, ioDispatcher = dispatcher)
        val holder = UpdateStateHolder(
            subject,
            UpdateDownloader(OkHttpClient(), directory, dispatcher) { error("unexpected download") },
            store,
            dispatcher,
        )
        try {
            holder.checkAutomatically()
            runCurrent()
            holder.checkNow()
            runCurrent()
            assertEquals("1.0.3", holder.state.value.available?.version)

            releaseFirst.complete(Unit)
            runCurrent()
            assertEquals("1.0.3", holder.state.value.available?.version)
            assertEquals(2, calls)
        } finally {
            releaseFirst.complete(Unit)
            holder.clear()
            runCurrent()
        }
    }

    @Test
    fun `clearing a holder prevents a late check from publishing`() = runTest {
        val store = InMemoryUpdateSettingsStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val subject = checker(store, { now }, {
            withContext(NonCancellable) { gate.await() }
            release("1.0.2")
        }, ioDispatcher = dispatcher)
        val holder = UpdateStateHolder(
            subject,
            UpdateDownloader(OkHttpClient(), directory, dispatcher) { error("unexpected download") },
            store,
            dispatcher,
        )

        holder.checkNow()
        runCurrent()
        holder.clear()
        val cleared = holder.state.value
        gate.complete(Unit)
        runCurrent()

        assertEquals(cleared, holder.state.value)
        assertEquals(0L, store.settings.value.lastCheckMillis)
    }

    @Test
    fun `blocking release work uses injected IO instead of the caller thread`() = runBlocking {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val callerThread = Thread.currentThread()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val subject = checker(InMemoryUpdateSettingsStore(), { now }, {
                assertFalse(Thread.currentThread() === callerThread)
                entered.countDown()
                check(gate.await(5, TimeUnit.SECONDS))
                release("1.0.2")
            }, ioDispatcher = io)
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                subject.check(manual = true)
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertTrue(job.isActive)
            } finally {
                gate.countDown()
                job.cancelAndJoin()
            }
        }
    }

    @Test
    fun `cancelling a slow release request cancels the blocked call`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .body("""{"tag_name":"v1.0.2"}""")
                    .headersDelay(2, TimeUnit.SECONDS)
                    .build(),
            )
            val started = CompletableDeferred<Call>()
            val cancelled = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(server.url("/latest")).build())
                }
                .eventListener(object : EventListener() {
                    override fun callStart(call: Call) {
                        started.complete(call)
                    }

                    override fun canceled(call: Call) {
                        cancelled.complete(Unit)
                    }
                })
                .build()
            val store = InMemoryUpdateSettingsStore()
            val subject = checker(store, { now }, GitHubReleaseSource(client))
            var published = false
            val job = launch(Dispatchers.IO) {
                subject.check(manual = true)
                published = true
            }
            try {
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                job.cancel()
                assertTrue(cancelled.isCompleted)
                withTimeout(1_000) { job.join() }
                assertTrue(started.await().isCanceled())
                assertFalse(published)
                assertEquals(0L, store.settings.value.lastCheckMillis)
            } finally {
                job.cancel()
                client.dispatcher.cancelAll()
            }
        }
    }
}

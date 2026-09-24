package dk.perspektiva.ttsroad.desktop.update

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.desktop.BuildInfo
import dk.perspektiva.ttsroad.desktop.data.AppLog
import dk.perspektiva.ttsroad.desktop.di.AppDispatchers
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Where the update check looks. Public releases only; the endpoint needs no credential. */
const val ReleaseRepositorySlug: String = "jonarihen/TTSRoad-Desktop"

/**
 * The published release, however it is obtained.
 *
 * A seam rather than a direct call so the whole update flow — throttling, comparison, asset
 * selection, dismissal — is testable without a network or a GitHub account.
 */
fun interface ReleaseSource {
    /** Returns the latest published release, or null when the project has never published one. */
    suspend fun latestRelease(): LatestRelease?
}

private data class GitHubAsset(
    val name: String? = null,
    val browser_download_url: String? = null,
    val size: Long? = null,
)

private data class GitHubRelease(
    val tag_name: String? = null,
    val name: String? = null,
    val body: String? = null,
    val html_url: String? = null,
    val draft: Boolean? = null,
    val prerelease: Boolean? = null,
    val assets: List<GitHubAsset>? = null,
)

/**
 * Reads `releases/latest` from GitHub over the application's shared HTTP client.
 *
 * Sharing the client is deliberate: `AuthInterceptor` attaches the TTSRoad bearer token only when
 * scheme, host and port match the signed-in server, so an api.github.com request carries no
 * credential. A separate client would duplicate the pool and, more importantly, would put a second
 * outbound path outside the rule that makes that true.
 */
class GitHubReleaseSource(
    private val client: OkHttpClient,
    private val repositorySlug: String = ReleaseRepositorySlug,
    private val ioDispatcher: CoroutineDispatcher = AppDispatchers.Default.io,
) : ReleaseSource {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(GitHubRelease::class.java)

    override suspend fun latestRelease(): LatestRelease? = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repositorySlug/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "${BuildInfo.APP_NAME}/${BuildInfo.VERSION}")
            // The 24-hour throttle is the caching policy; a stale cached body would defeat a
            // manual "check now" the user pressed precisely because they expect a fresh answer.
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()

        client.newCall(request).useCancellable { response, context ->
            // A project with no releases answers 404. That is "nothing published", not a failure.
            if (response.code == 404) return@useCancellable null
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            val payload = response.readText(context)
            val release = adapter.fromJson(payload) ?: return@useCancellable null
            context.ensureActive()
            if (release.draft == true || release.prerelease == true) return@useCancellable null
            val tag = release.tag_name?.takeIf { it.isNotBlank() } ?: return@useCancellable null
            LatestRelease(
                tag = tag,
                version = tag.removePrefix("v"),
                notes = release.body.orEmpty().trim(),
                htmlUrl = release.html_url.orEmpty(),
                assets = release.assets.orEmpty().mapNotNull { asset ->
                    val name = asset.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val url = asset.browser_download_url?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    ReleaseAsset(name = name, browserDownloadUrl = url, sizeBytes = asset.size ?: 0L)
                },
            )
        }
    }
}

/**
 * Decides whether to check, performs the check, and records the outcome.
 *
 * Deliberately free of Compose and of any I/O it does not own: the source is injected, the clock is
 * injected, and the persisted state is a store. That is what lets the acceptance cases — throttled,
 * dismissed, no asset for this platform, network failure — be ordinary unit tests.
 */
class UpdateChecker(
    private val source: ReleaseSource,
    private val settingsStore: UpdateSettingsStore,
    private val installedVersion: String = BuildInfo.VERSION,
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val architecture: String = System.getProperty("os.arch").orEmpty(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = AppDispatchers.Default.io,
) {
    private val checkedThisLaunch = AtomicBoolean()

    /**
     * Runs a check unless an automatic one is not due yet.
     *
     * [manual] bypasses the throttle and the "already checked" flag, because a user who presses
     * the button is asking for a network round trip, not for the cached verdict.
     */
    suspend fun check(manual: Boolean): UpdateStatus = withContext(ioDispatcher) {
        currentCoroutineContext().ensureActive()
        val settings = settingsStore.settings.value
        if (!manual) {
            if (!shouldCheckAutomatically(settings, clock(), checkedThisLaunch.get()) ||
                !checkedThisLaunch.compareAndSet(false, true)
            ) {
                return@withContext UpdateStatus.Unknown
            }
        }
        checkedThisLaunch.set(true)

        val release = try {
            source.latestRelease()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            // The reason is a short human sentence. A response body could carry anything, so it
            // never becomes UI text.
            AppLog.warn("the update check could not reach GitHub", failure)
            return@withContext UpdateStatus.Failed("Could not reach the update server")
        } catch (failure: RuntimeException) {
            currentCoroutineContext().ensureActive()
            AppLog.warn("the update check returned something unreadable", failure)
            return@withContext UpdateStatus.Failed("The update information could not be read")
        }

        currentCoroutineContext().ensureActive()
        val now = clock()
        settingsStore.update { it.copy(lastCheckMillis = now) }
        currentCoroutineContext().ensureActive()

        if (release == null || !isNewerVersion(release.version, installedVersion)) {
            return@withContext UpdateStatus.UpToDate(now)
        }
        // A dismissal covers exactly the version it was made against. A newer one asks again.
        if (!manual && settingsStore.settings.value.dismissedVersion == release.version) {
            return@withContext UpdateStatus.UpToDate(now)
        }

        UpdateStatus.Available(
            release = release,
            asset = selectAssetFor(release.assets, osName, architecture),
        )
    }

    /** Stops this one version from being announced again; a later release still is. */
    fun dismiss(version: String) {
        settingsStore.update { it.copy(dismissedVersion = version) }
    }

    fun setAutomatic(enabled: Boolean) {
        settingsStore.update { it.copy(automatic = enabled) }
    }
}

/** Why a download did not end in a file the user can install. */
sealed interface DownloadOutcome {
    /** The file is verified and on disk. Installing it is the user's next, explicit action. */
    data class Verified(val file: File) : DownloadOutcome

    data class Failed(val reason: String) : DownloadOutcome
}

/**
 * Downloads a release asset and refuses to hand over anything it could not verify.
 *
 * The rule that matters: a file whose SHA-256 does not match the published `SHA256SUMS` entry is
 * deleted and never opened. Nothing here installs anything — no `sudo`, no package manager call.
 * The verified file is handed to the desktop, which is what asks the user for authorisation.
 */
class UpdateDownloader(
    private val client: OkHttpClient,
    private val targetDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = AppDispatchers.Default.io,
    private val open: (File) -> Unit = ::openWithDesktop,
) {
    suspend fun download(release: LatestRelease, asset: ReleaseAsset): DownloadOutcome {
        var attemptDirectory: File? = null
        var retained = false
        try {
            return withContext(ioDispatcher) {
                currentCoroutineContext().ensureActive()
                if (asset.name.isBlank() || asset.name == "." || asset.name == ".." ||
                    asset.name.any { it == '/' || it == '\\' }
                ) {
                    return@withContext DownloadOutcome.Failed("The installer filename is invalid")
                }
                val checksumAsset = release.assets.firstOrNull { it.name == ChecksumAssetName }
                    ?: return@withContext DownloadOutcome.Failed("The release publishes no checksums")

                val expected = try {
                    parseChecksums(fetchText(checksumAsset.browserDownloadUrl))[asset.name]
                } catch (failure: IOException) {
                    currentCoroutineContext().ensureActive()
                    AppLog.warn("could not download the release checksums", failure)
                    return@withContext DownloadOutcome.Failed("Could not download the checksums")
                } ?: return@withContext DownloadOutcome.Failed("The checksums do not cover ${asset.name}")

                currentCoroutineContext().ensureActive()
                targetDirectory.mkdirs()
                val directory = Files.createTempDirectory(targetDirectory.toPath(), "download-").toFile()
                attemptDirectory = directory
                val partial = File(directory, "${asset.name}.part")
                val target = File(directory, asset.name)
                val actual = downloadTo(asset.browserDownloadUrl, partial)

                currentCoroutineContext().ensureActive()
                if (!actual.equals(expected, ignoreCase = true)) {
                    AppLog.warn("rejected a release asset whose checksum did not match")
                    return@withContext DownloadOutcome.Failed("The download failed its checksum check and was deleted")
                }

                currentCoroutineContext().ensureActive()
                if (!partial.renameTo(target)) {
                    return@withContext DownloadOutcome.Failed("The verified download could not be saved")
                }
                currentCoroutineContext().ensureActive()
                try {
                    open(target)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    AppLog.warn("could not hand the installer to the desktop", failure)
                }
                retained = true
                currentCoroutineContext().ensureActive()
                DownloadOutcome.Verified(target)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            AppLog.warn("could not download the release asset", failure)
            return DownloadOutcome.Failed("The download did not complete")
        } finally {
            if (!retained) {
                withContext(NonCancellable + ioDispatcher) {
                    attemptDirectory?.deleteRecursively()
                }
            }
        }
    }

    private suspend fun fetchText(url: String): String {
        val request = Request.Builder().url(url).build()
        return client.newCall(request).useCancellable { response, context ->
            if (!response.isSuccessful) throw IOException("download answered ${response.code}")
            response.readText(context)
        }
    }

    /** Streams to disk and returns the hex SHA-256 of what was actually written. */
    private suspend fun downloadTo(url: String, destination: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val request = Request.Builder().url(url).build()
        client.newCall(request).useCancellable { response, context ->
            if (!response.isSuccessful) throw IOException("download answered ${response.code}")
            val body = response.body
            destination.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        context.ensureActive()
                        val read = input.read(buffer)
                        context.ensureActive()
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
                context.ensureActive()
                output.flush()
            }
        }
        currentCoroutineContext().ensureActive()
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

private suspend fun <T> Call.useCancellable(block: (Response, CoroutineContext) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        val result = try {
            continuation.context.ensureActive()
            execute().use { response ->
                continuation.context.ensureActive()
                block(response, continuation.context)
            }
        } catch (failure: Exception) {
            continuation.resumeWithException(failure)
            return@suspendCancellableCoroutine
        }
        continuation.resume(result)
    }

private fun Response.readText(context: CoroutineContext): String =
    body.charStream().use { reader ->
        val result = StringBuilder()
        val buffer = CharArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            context.ensureActive()
            val read = reader.read(buffer)
            context.ensureActive()
            if (read < 0) break
            result.append(buffer, 0, read)
        }
        result.toString()
    }

/**
 * Hands a verified file to the desktop's own handler.
 *
 * Never a package-manager invocation: installing a system package is an authorised action that
 * belongs to the desktop's installer, which prompts. An application that ran `sudo dpkg -i` itself
 * would be asking the user to trust it with far more than an update.
 */
private fun openWithDesktop(file: File) {
    if (Desktop.isDesktopSupported()) {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.OPEN)) {
            desktop.open(file)
            return
        }
    }
    // Headless or a desktop without OPEN support: showing the folder is the honest fallback.
    AppLog.info("no desktop handler for ${file.name}; it is in ${file.parent}")
}

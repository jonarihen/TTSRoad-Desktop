package dk.perspektiva.ttsroad.desktop.update

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.desktop.BuildInfo
import dk.perspektiva.ttsroad.desktop.data.AppLog
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

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
) : ReleaseSource {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(GitHubRelease::class.java)

    override suspend fun latestRelease(): LatestRelease? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repositorySlug/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "${BuildInfo.APP_NAME}/${BuildInfo.VERSION}")
            // The 24-hour throttle is the caching policy; a stale cached body would defeat a
            // manual "check now" the user pressed precisely because they expect a fresh answer.
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()

        client.newCall(request).execute().use { response ->
            // A project with no releases answers 404. That is "nothing published", not a failure.
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            val payload = response.body.string()
            val release = adapter.fromJson(payload) ?: return null
            if (release.draft == true || release.prerelease == true) return null
            val tag = release.tag_name?.takeIf { it.isNotBlank() } ?: return null
            return LatestRelease(
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
) {
    private var checkedThisLaunch = false

    /**
     * Runs a check unless an automatic one is not due yet.
     *
     * [manual] bypasses the throttle and the "already checked" flag, because a user who presses
     * the button is asking for a network round trip, not for the cached verdict.
     */
    suspend fun check(manual: Boolean): UpdateStatus {
        val settings = settingsStore.settings.value
        if (!manual && !shouldCheckAutomatically(settings, clock(), checkedThisLaunch)) {
            return UpdateStatus.Unknown
        }
        checkedThisLaunch = true

        val release = try {
            source.latestRelease()
        } catch (failure: IOException) {
            // The reason is a short human sentence. A response body could carry anything, so it
            // never becomes UI text.
            AppLog.warn("the update check could not reach GitHub", failure)
            return UpdateStatus.Failed("Could not reach the update server")
        } catch (failure: RuntimeException) {
            AppLog.warn("the update check returned something unreadable", failure)
            return UpdateStatus.Failed("The update information could not be read")
        }

        val now = clock()
        settingsStore.update { it.copy(lastCheckMillis = now) }

        if (release == null || !isNewerVersion(release.version, installedVersion)) {
            return UpdateStatus.UpToDate(now)
        }
        // A dismissal covers exactly the version it was made against. A newer one asks again.
        if (!manual && settings.dismissedVersion == release.version) return UpdateStatus.UpToDate(now)

        return UpdateStatus.Available(
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
    private val open: (File) -> Unit = ::openWithDesktop,
) {
    suspend fun download(release: LatestRelease, asset: ReleaseAsset): DownloadOutcome {
        val checksumAsset = release.assets.firstOrNull { it.name == ChecksumAssetName }
            ?: return DownloadOutcome.Failed("The release publishes no checksums")

        val expected = try {
            parseChecksums(fetchText(checksumAsset.browserDownloadUrl))[asset.name]
        } catch (failure: IOException) {
            AppLog.warn("could not download the release checksums", failure)
            return DownloadOutcome.Failed("Could not download the checksums")
        } ?: return DownloadOutcome.Failed("The checksums do not cover ${asset.name}")

        targetDirectory.mkdirs()
        // A partial file never carries the final name, so an interrupted download cannot be
        // mistaken for a verified one by this or any later run.
        val partial = File(targetDirectory, "${asset.name}.part")
        val target = File(targetDirectory, asset.name)
        val actual = try {
            downloadTo(asset.browserDownloadUrl, partial)
        } catch (failure: IOException) {
            partial.delete()
            AppLog.warn("could not download the release asset", failure)
            return DownloadOutcome.Failed("The download did not complete")
        }

        if (!actual.equals(expected, ignoreCase = true)) {
            partial.delete()
            AppLog.warn("rejected a release asset whose checksum did not match")
            return DownloadOutcome.Failed("The download failed its checksum check and was deleted")
        }

        target.delete()
        if (!partial.renameTo(target)) {
            partial.delete()
            return DownloadOutcome.Failed("The verified download could not be saved")
        }
        runCatching { open(target) }
            .onFailure { AppLog.warn("could not hand the installer to the desktop", it) }
        return DownloadOutcome.Verified(target)
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("download answered ${response.code}")
            return response.body.string()
        }
    }

    /** Streams to disk and returns the hex SHA-256 of what was actually written. */
    private fun downloadTo(url: String, destination: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("download answered ${response.code}")
            val body = response.body
            destination.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
                output.flush()
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
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

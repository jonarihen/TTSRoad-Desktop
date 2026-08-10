package dk.perspektiva.ttsroad.desktop.update

/**
 * One downloadable file attached to a GitHub release.
 *
 * [browserDownloadUrl] is an absolute URL on a GitHub host, which is exactly why the update code
 * uses the shared [okhttp3.OkHttpClient]: the auth interceptor's same-origin rule means the
 * TTSRoad bearer token is not attached to it.
 */
data class ReleaseAsset(
    val name: String,
    val browserDownloadUrl: String,
    val sizeBytes: Long,
)

/** The published release the update check compares against, with its notes and assets. */
data class LatestRelease(
    val tag: String,
    val version: String,
    val notes: String,
    val htmlUrl: String,
    val assets: List<ReleaseAsset>,
)

/** What the UI renders. A check that failed is not the same as a check that found nothing. */
sealed interface UpdateStatus {
    /** No check has run yet in this session. */
    data object Unknown : UpdateStatus

    data object Checking : UpdateStatus

    /** The check succeeded and this build is current. */
    data class UpToDate(val checkedAtMillis: Long) : UpdateStatus

    /**
     * A newer release exists. [asset] is null when the release carries nothing for this
     * platform/architecture, which is a real outcome rather than an error: the user is told a
     * version exists and pointed at the release page instead of a download button that lies.
     */
    data class Available(
        val release: LatestRelease,
        val asset: ReleaseAsset?,
    ) : UpdateStatus

    /** The check itself failed. The message is for a human; it never carries a response body. */
    data class Failed(val reason: String) : UpdateStatus
}

/**
 * Compares two dotted numeric versions.
 *
 * Only the numeric core is ordered. A pre-release suffix (`1.2.0-rc1`) sorts *below* the same core
 * release, per SemVer, so an installed release build is never asked to "update" to its own
 * candidate. Anything unparseable compares as 0, which makes an unknown version look older than a
 * known one rather than newer — the safe direction for something that offers downloads.
 */
fun compareVersions(left: String, right: String): Int {
    val (leftCore, leftPre) = splitVersion(left)
    val (rightCore, rightPre) = splitVersion(right)
    val width = maxOf(leftCore.size, rightCore.size)
    for (index in 0 until width) {
        val comparison = leftCore.getOrElse(index) { 0 }.compareTo(rightCore.getOrElse(index) { 0 })
        if (comparison != 0) return comparison
    }
    return when {
        leftPre == rightPre -> 0
        leftPre == null -> 1
        rightPre == null -> -1
        else -> leftPre.compareTo(rightPre)
    }
}

private fun splitVersion(version: String): Pair<List<Int>, String?> {
    val trimmed = version.trim().removePrefix("v")
    val core = trimmed.substringBefore('-').substringBefore('+')
    val preRelease = trimmed.substringAfter('-', "").takeIf { it.isNotEmpty() }
    val numbers = core.split('.').map { part -> part.trim().toIntOrNull() ?: 0 }
    return numbers to preRelease
}

/** True when [candidate] is a strictly newer release than [installed]. */
fun isNewerVersion(candidate: String, installed: String): Boolean =
    compareVersions(candidate, installed) > 0

/** The three formats a release publishes, one per operating system. */
private val LinuxArchitectures = setOf("amd64", "x86_64", "x64")

/**
 * Picks the asset this machine can actually install, or null when the release has none.
 *
 * Architecture is only enforced where more than one is conceivable. Linux publishes an `amd64`
 * package and nothing else, so an aarch64 desktop gets null rather than a package `dpkg` would
 * refuse — offering it would be a download that cannot succeed.
 */
fun selectAssetFor(
    assets: List<ReleaseAsset>,
    osName: String,
    architecture: String,
): ReleaseAsset? {
    val os = osName.lowercase()
    val arch = architecture.lowercase()
    return when {
        os.contains("win") -> assets.firstOrNull { it.name.endsWith(".msi", ignoreCase = true) }
        os.contains("mac") || os.contains("darwin") ->
            assets.firstOrNull { it.name.endsWith(".dmg", ignoreCase = true) }
        arch in LinuxArchitectures ->
            assets.firstOrNull { it.name.endsWith("_amd64.deb", ignoreCase = true) }
        else -> null
    }
}

/**
 * Reads a `sha256sum` output file into name → digest.
 *
 * Both `sha256sum` spellings appear in the wild — `<digest>  name` and `<digest> *name` — and the
 * release workflow writes names as `./name`, so the leading `./` is stripped. A malformed line is
 * skipped rather than failing the whole file, but a name that is therefore absent later fails the
 * verification, which is the outcome that matters.
 */
fun parseChecksums(text: String): Map<String, String> = buildMap {
    for (line in text.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val digest = trimmed.substringBefore(' ').lowercase()
        if (digest.length != 64 || !digest.all { it in '0'..'9' || it in 'a'..'f' }) continue
        val name = trimmed.substringAfter(' ').trim().removePrefix("*").removePrefix("./")
        if (name.isNotEmpty()) put(name, digest)
    }
}

/** The name of the checksum asset the release workflow publishes. */
const val ChecksumAssetName: String = "SHA256SUMS"

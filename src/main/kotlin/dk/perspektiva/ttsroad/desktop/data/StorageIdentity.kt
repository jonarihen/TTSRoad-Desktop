package dk.perspektiva.ttsroad.desktop.data

import java.security.MessageDigest
import java.util.Locale

/**
 * Who downloaded bytes belong to, and therefore which directory they live in.
 *
 * The issue's rule is that content is namespaced by *stable server identity*, *account identity*
 * and *chapter id* — never by fiction name, raw connect URL, or a filename the server chose. Each
 * of those three exclusions is a concrete failure this type exists to prevent:
 *
 * - **Fiction name** collides. Two servers both hosting "Chapter 1" of a serial with the same title
 *   would share a file and serve one's audio for the other's chapter.
 * - **Raw connect URL** is not identity, it is an address. Moving a server from
 *   `http://192.168.1.10:8000` to `https://ttsroad.example` would orphan every download on the
 *   machine and silently re-download the lot — which is exactly the acceptance criterion about an
 *   address change not forcing needless duplication.
 * - **Server-chosen filenames** are untrusted input. `../../../.ssh/authorized_keys` is a valid
 *   string in a JSON field, and a path assembled from one is a directory traversal.
 *
 * So the address is used only as a *fallback* identity, and only when the server has not advertised
 * a stable one; the on-disk name is always a hash this app computed.
 */
data class StorageIdentity(
    /** Stable per-server component, already hashed and filesystem-safe. */
    val serverKey: String,
    /** Stable per-account component, already hashed and filesystem-safe. */
    val accountKey: String,
) {
    /**
     * The single directory segment pair, `<server>/<account>`.
     *
     * Two segments rather than one concatenated key so the Settings screen can total a server's
     * usage by listing one directory, and so signing out of one account on a shared machine leaves
     * a visibly separate tree rather than an interleaved one.
     */
    val relativePath: String get() = "$serverKey/$accountKey"

    companion object {
        /**
         * How many hex characters of the digest are kept.
         *
         * 128 bits. Long enough that a collision between two servers or two accounts on one desktop
         * is not a thing that happens, short enough to keep paths readable in a bug report and well
         * inside every filesystem's component limit.
         */
        const val KeyLength: Int = 32

        /** What an account key is when nobody is signed in — downloads still need somewhere to go. */
        const val AnonymousAccount: String = "anonymous"

        /**
         * Derives the identity for a session.
         *
         * [advertisedBaseUrl] is `server.base_url` from capability discovery. It is preferred over
         * [connectUrl] precisely because it is what the server says it *is* rather than how this
         * client happened to reach it: a LAN address, a tailnet name and a public hostname are
         * three routes to one library, and they must resolve to one directory.
         */
        fun of(
            connectUrl: String,
            advertisedBaseUrl: String? = null,
            username: String? = null,
        ): StorageIdentity = StorageIdentity(
            serverKey = serverKey(connectUrl, advertisedBaseUrl),
            accountKey = accountKey(username),
        )

        fun serverKey(connectUrl: String, advertisedBaseUrl: String? = null): String {
            val identity = advertisedBaseUrl?.takeIf { it.isNotBlank() } ?: connectUrl
            return digest("server:${canonicalOrigin(identity)}")
        }

        /**
         * Account component.
         *
         * Trimmed but **not** case-folded. The name stored in the session is
         * `response.user.username` — the server's own spelling — so folding it would buy nothing
         * and would merge two genuinely distinct accounts on a server that treats case as
         * significant, which is Django's default. Two accounts sharing a download directory is a
         * disclosure; one account occasionally splitting is a re-download.
         */
        fun accountKey(username: String?): String {
            val name = username?.trim().orEmpty()
            if (name.isEmpty()) return AnonymousAccount
            return digest("account:$name")
        }

        /**
         * Scheme, host, port **and path**, with only the case-insensitive parts folded.
         *
         * Deliberately *not* a general URL parser, and deliberately not origin-only. Scheme and
         * host are case-insensitive, so `https://Host.Example/` and `https://host.example` are one
         * server. A **path is not**, and it is part of the identity: `normalizeBaseUrl` keeps
         * whatever path the user configured and Retrofit supports path-based base URLs, so
         * `https://host/ttsroad/` and `https://host/other/` are two deployments that may hold
         * completely different libraries under the same chapter ids.
         *
         * Dropping the path would have merged them into one download directory, where one server's
         * audio is served for the other's chapter. That is the failure this whole type exists to
         * prevent, and it is strictly worse than the alternative: an identity that splits when it
         * should not costs a re-download, an identity that merges serves the wrong content. Every
         * ambiguous call here is resolved that way.
         *
         * The scheme is kept for the same reason — `http://host` and `https://host` may genuinely
         * be different deployments.
         */
        fun canonicalOrigin(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            val schemeEnd = trimmed.indexOf("://")
            if (schemeEnd <= 0) return trimmed.lowercase(Locale.ROOT)

            val scheme = trimmed.substring(0, schemeEnd).lowercase(Locale.ROOT)
            val rest = trimmed.substring(schemeEnd + 3)
            val authorityEnd = rest.indexOf('/').takeIf { it >= 0 } ?: rest.length
            val authority = rest.substring(0, authorityEnd)
            // Credentials in a URL are not identity and must never reach a directory name.
            val hostPort = authority.substringAfterLast('@').lowercase(Locale.ROOT)

            val defaultPort = if (scheme == "https") "443" else "80"
            val host = hostPort.substringBeforeLast(':', hostPort)
            val port = hostPort.substringAfterLast(':', "").takeIf { it != hostPort }.orEmpty()
            val normalisedPort = if (port.isEmpty() || port == defaultPort) "" else ":$port"

            // Case preserved, and the query/fragment dropped — neither names a deployment.
            val path = rest.substring(authorityEnd).substringBefore('?').substringBefore('#').trimEnd('/')
            return "$scheme://$host$normalisedPort$path"
        }

        /**
         * Lowercase hex SHA-256, truncated to [KeyLength].
         *
         * A hash rather than an escaped hostname so the result is fixed-length, filesystem-safe on
         * every platform, and free of anything the server controls. It is not a secret and is not
         * meant to be one — it prevents *collisions and traversal*, not inspection.
         */
        private fun digest(input: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(KeyLength)
    }
}

/**
 * The name a chapter's audio is stored under.
 *
 * Generated from the chapter id, never from the server's filename or the chapter title. The
 * extension is fixed rather than taken from the URL for the same reason: a server that serves
 * `chapter.mp3.exe` should not get to name a file on this disk.
 *
 * [contentHash] is the forward-compatibility hook for the advertised `audio_content_hash`
 * capability. When a server starts reporting it, re-encoded audio changes the name and the stale
 * download is replaced instead of being served forever; until then the id alone is the name, which
 * is why this takes a nullable rather than requiring the feature now.
 */
fun chapterFileName(chapterId: Int, contentHash: String? = null): String {
    val safeHash = contentHash?.filter { it.isLetterOrDigit() }?.take(16)?.takeIf { it.isNotEmpty() }
    return if (safeHash == null) "$chapterId.mp3" else "$chapterId-$safeHash.mp3"
}

/** The in-progress name for [chapterFileName]. Never reported as offline; see `DownloadStore`. */
fun partFileName(chapterId: Int, contentHash: String? = null): String =
    chapterFileName(chapterId, contentHash) + ".part"

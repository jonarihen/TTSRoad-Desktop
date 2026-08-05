package dk.perspektiva.ttsroad.desktop.data

/**
 * Pure URL helpers shared by the repository, the audio download store, and the cover-image
 * loading in the UI. Kept free of any session/HTTP dependency so they can be unit-tested
 * directly (see `ServerUrlsTest`).
 *
 * The exception message from [normalizeBaseUrl] is part of the contract: the login screen
 * renders it verbatim when the user types a URL without a scheme.
 */

/**
 * Trims whitespace and any trailing slashes, requires an http/https scheme, and appends exactly
 * one trailing slash (Retrofit rejects a base URL without one).
 */
fun normalizeBaseUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        "Server URL must start with http:// or https://"
    }
    return "$trimmed/"
}

/**
 * Audio/cover URLs from the API are normally absolute (the server prefixes them with its
 * configured BASE_URL), but fall back to a bare path like `/audio/slug/file.mp3` if the admin
 * hasn't set BASE_URL. Resolve that case against [serverUrl].
 *
 * Absolute URLs are returned untouched regardless of origin — cover images in particular are
 * frequently served from a third-party CDN (Royal Road), so this must never rewrite them onto
 * the TTSRoad host.
 */
fun resolveAgainstServer(serverUrl: String, url: String): String {
    if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
        return url
    }
    val base = serverUrl.trimEnd('/')
    return if (url.startsWith("/")) base + url else "$base/$url"
}

package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Moshi

/**
 * Why a stored bearer token stopped working.
 *
 * The server distinguishes these in the 401 body (`app/services/mobile_auth.py`), and the
 * difference is the whole reason the login screen can say something better than "signed out": a
 * token that aged out after 90 days idle is a shrug, one revoked from another device is worth
 * knowing about.
 */
enum class SessionEndReason {
    /** Unused for 90 days. Any authenticated request renews the expiry, so this means real disuse. */
    Expired,

    /** Signed out from the web console or from another device's session list. */
    Revoked,

    /** The server does not recognise the token at all — a reset database, or a mangled value. */
    Invalid,

    /** A 401 with no reason this build understands: an older server, a proxy, or an empty body. */
    Unknown,
}

/**
 * A finished session: why it ended, and the line to show on the login screen.
 *
 * [message] is the server's own wording whenever it sends one, because the backend is the only
 * thing that knows *which* of several sessions was revoked and when.
 */
data class SessionEnd(
    val reason: SessionEndReason,
    val message: String,
)

/**
 * A 429 from the login endpoint (`app/services/login_throttle.py`).
 *
 * [retryAfterSeconds] comes from the `Retry-After` header when present and from the body's
 * `detail.retry_after` otherwise, so a proxy that strips the header does not lose the wait.
 */
data class LoginThrottle(
    val message: String,
    val retryAfterSeconds: Int?,
) {
    /** "Too many failed attempts — try again in 15 minutes". */
    val displayMessage: String
        get() = retryAfterSeconds?.let { "$message — try again in ${formatRetryAfter(it)}" } ?: message
}

internal fun formatRetryAfter(seconds: Int): String = when {
    seconds <= 1 -> "a moment"
    seconds < 60 -> "$seconds seconds"
    seconds < 120 -> "a minute"
    seconds < 3_600 -> "${seconds / 60} minutes"
    seconds < 7_200 -> "an hour"
    else -> "${seconds / 3_600} hours"
}

/** FastAPI's `{"detail": …}` error body, which is either a bare string or an object. */
private data class ErrorDetail(
    val message: String?,
    val reason: String?,
    val retryAfterSeconds: Int?,
)

// Plain Moshi: the error body is read as a generic map, so the Kotlin reflection factory the DTOs
// need is not involved here — and a malformed body can never take down the sign-out path.
private val errorMoshi = Moshi.Builder().build()

private fun parseErrorDetail(body: String?): ErrorDetail {
    val empty = ErrorDetail(message = null, reason = null, retryAfterSeconds = null)
    if (body.isNullOrBlank()) return empty
    return try {
        val parsed = errorMoshi.adapter(Any::class.java).fromJson(body) as? Map<*, *>
        when (val detail = parsed?.get("detail")) {
            is String -> ErrorDetail(message = detail, reason = null, retryAfterSeconds = null)
            is Map<*, *> -> ErrorDetail(
                message = detail["message"] as? String,
                reason = detail["reason"] as? String,
                // Moshi parses every JSON number as a Double.
                retryAfterSeconds = (detail["retry_after"] as? Number)?.toInt(),
            )

            else -> empty
        }
    } catch (_: Exception) {
        empty
    }
}

/** Pull a human-readable message out of FastAPI's `{"detail": …}` error body. */
fun detailMessage(body: String?): String? = parseErrorDetail(body).message?.takeIf { it.isNotBlank() }

/**
 * Read the structured 401 the server sends when a bearer token can no longer be used:
 *
 * ```json
 * {"detail":{"message":"This device session expired. Sign in again.","reason":"token_expired"}}
 * ```
 *
 * Always returns a [SessionEnd] rather than null, because this is only called once a 401 has
 * already decided the session is over — an unreadable body costs the explanation, not the
 * sign-out. The same body shape comes back from bearer-authenticated `/audio/…` requests, so the
 * playback path funnels through here too.
 */
fun parseSessionEnd(body: String?): SessionEnd {
    val detail = parseErrorDetail(body)
    val reason = when (detail.reason?.trim()?.lowercase()) {
        "token_expired" -> SessionEndReason.Expired
        "token_revoked" -> SessionEndReason.Revoked
        "invalid_token" -> SessionEndReason.Invalid
        else -> SessionEndReason.Unknown
    }
    return SessionEnd(
        reason = reason,
        // The server's own message wins, but it is redacted first: it is echoed into the UI and
        // there is no contract stopping a future build from quoting the offending credential.
        message = redactSecrets(detail.message).takeIf { it.isNotBlank() } ?: defaultMessage(reason),
    )
}

/**
 * Read the 429 the login endpoint sends when the username or the client IP is throttled.
 *
 * [retryAfterHeader] is the raw `Retry-After` value; TTSRoad always sends it as a number of
 * seconds (`app/routers/mobile.py`), and a non-numeric HTTP-date form is ignored rather than
 * guessed at.
 */
fun parseLoginThrottle(body: String?, retryAfterHeader: String?): LoginThrottle {
    val detail = parseErrorDetail(body)
    return LoginThrottle(
        message = redactSecrets(detail.message).takeIf { it.isNotBlank() } ?: "Too many failed attempts",
        retryAfterSeconds = retryAfterHeader?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
            ?: detail.retryAfterSeconds?.takeIf { it >= 0 },
    )
}

private fun defaultMessage(reason: SessionEndReason): String = when (reason) {
    SessionEndReason.Expired -> "This device session expired - sign in again"
    SessionEndReason.Revoked -> "This device was signed out - sign in again"
    SessionEndReason.Invalid -> "The server no longer accepts this session - sign in again"
    SessionEndReason.Unknown -> "Session expired - sign in again"
}

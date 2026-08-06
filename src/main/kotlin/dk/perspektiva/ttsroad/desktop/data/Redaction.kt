package dk.perspektiva.ttsroad.desktop.data

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Secret scrubbing for everything that leaves the process as text: log lines, UI error strings,
 * and anything a user might paste into a bug report.
 *
 * The rule is that a secret must never reach a `String` that is displayed or written, so this is
 * applied at the *boundary* (log call, error mapping) rather than trusted to callers. It is
 * deliberately over-eager: a redacted diagnostic is annoying, a leaked bearer token is a breach.
 */

/** What a removed secret is replaced with. Short and obviously non-random. */
const val RedactionPlaceholder: String = "***"

// `Authorization: Bearer x`, `authorization=x`, and the `"Authorization": "x"` JSON/mapped form.
private val AuthorizationHeader =
    Regex("""(?i)(authorization"?\s*[:=]\s*"?)(?:bearer\s+)?[^\s"',;}\]]+""")

// TTSRoad bearer tokens are `ttsr_` + a url-safe base64 blob (app/services/mobile_auth.py).
private val TtsRoadToken = Regex("""ttsr_[A-Za-z0-9_\-]+""")

// Secrets that servers and proxies love to put in query strings.
private val SensitiveQueryParam =
    Regex("""(?i)([?&](?:token|access_token|api_key|apikey|password|passwd|pwd|totp_code|code|secret|feed_token)=)[^&\s"'>]*""")

// The same names inside a JSON body.
private val SensitiveJsonField =
    Regex("""(?i)("(?:token|access_token|password|totp_code|secret|credential|credential_key)"\s*:\s*)"[^"]*"""")

// https://user:password@host — the userinfo half of a URL.
private val UrlUserInfo = Regex("""(?i)(https?://)[^/\s:@]+:[^/\s@]+@""")

/**
 * Removes credentials from [text].
 *
 * Returns `""` for null so callers can `ifBlank { … }` without a second null check.
 */
fun redactSecrets(text: String?): String {
    if (text.isNullOrEmpty()) return ""
    return text
        .replace(AuthorizationHeader) { it.groupValues[1] + RedactionPlaceholder }
        .replace(SensitiveJsonField) { it.groupValues[1] + "\"$RedactionPlaceholder\"" }
        .replace(SensitiveQueryParam) { it.groupValues[1] + RedactionPlaceholder }
        .replace(UrlUserInfo) { it.groupValues[1] + "$RedactionPlaceholder@" }
        .replace(TtsRoadToken, RedactionPlaceholder)
}

/**
 * Turns a transport-level failure into one sentence a user can act on.
 *
 * Two things are deliberately absent: stack traces (they are noise to a user and a leak risk once
 * pasted into an issue) and the raw exception text for the cases below, which tends to be
 * `javax.net.ssl.SSLHandshakeException: PKIX path building failed: …`. Anything not recognised
 * falls through to the exception's own message, redacted.
 */
fun describeNetworkFailure(error: Throwable): String = when (error) {
    is UnknownHostException -> "Cannot reach that server — its address did not resolve"
    is SSLPeerUnverifiedException -> "The server's TLS certificate is not valid for that address"
    is SSLHandshakeException -> "The server's TLS certificate was not accepted"
    is javax.net.ssl.SSLException -> "The secure connection to the server failed"
    is SocketTimeoutException -> "The server did not respond in time"
    is ConnectException -> "Could not connect to the server"
    is NoRouteToHostException -> "No route to that server"
    else -> redactSecrets(error.message).ifBlank { error::class.simpleName ?: "Request failed" }
}

/**
 * [describeNetworkFailure] for a screen that has its own wording for "nothing loaded".
 *
 * `describeNetworkFailure` ends at the exception's class name when there is no message at all,
 * which is fine in a log line and useless in the UI — a user does not need to know that a
 * `RuntimeException` happened. In that one case the caller's [fallback] wins.
 */
fun userFacingMessage(error: Throwable, fallback: String): String {
    val described = describeNetworkFailure(error)
    return if (described == error::class.simpleName) fallback else described
}

/**
 * The whole logging surface of the app.
 *
 * There is no logging framework here on purpose — one call site, one redaction point. Adding a
 * framework later must keep that property: nothing writes a message that has not been through
 * [redactSecrets].
 */
object AppLog {
    /** Set by tests to capture output; production writes to stderr. */
    @Volatile
    var sink: (String) -> Unit = { System.err.println(it) }

    fun warn(message: String, error: Throwable? = null) {
        val detail = error?.let { " — ${describeNetworkFailure(it)}" }.orEmpty()
        sink("[TTSRoad] ${redactSecrets(message + detail)}")
    }
}

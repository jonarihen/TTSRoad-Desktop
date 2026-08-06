package dk.perspektiva.ttsroad.desktop.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Request marker meaning "this call is public — do not attach a credential".
 *
 * It is a request header rather than a flag on the Retrofit method because Retrofit's only way to
 * annotate a call is a header, and [TtsRoadAuthInterceptor] strips it before the request reaches
 * the socket, so it never appears on the wire.
 */
const val NoAuthHeader: String = "X-TtsRoad-No-Auth"

/** The bearer credential and the origin it is valid for. */
data class BearerCredentials(
    val serverUrl: String,
    val header: String,
)

/**
 * The one place a bearer token is attached to an outgoing request.
 *
 * Every subsystem — Retrofit API calls, chapter audio downloads, and Coil's cover fetches — shares
 * a single OkHttpClient carrying this interceptor, so there is exactly one answer to "when is the
 * token sent". Two rules make that answer safe:
 *
 * 1. **Same origin only.** Cover images are frequently absolute URLs on a third-party CDN
 *    (Royal Road), and Coil fetches them through the same client. Scheme/host/port must match the
 *    signed-in server or the request goes out bare.
 * 2. **[NoAuthHeader] wins.** Capability discovery runs against a URL the user is still typing,
 *    which may be a completely different host that happens to share an origin with a stale
 *    session. Marking it public means a previous server's token is never offered to it.
 */
class TtsRoadAuthInterceptor(
    private val credentials: () -> BearerCredentials?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.header(NoAuthHeader) != null) {
            return chain.proceed(request.newBuilder().removeHeader(NoAuthHeader).build())
        }
        // An explicit header on the call wins; nothing in the app sets one, but a future caller
        // that does should not be silently overwritten.
        if (request.header("Authorization") != null) return chain.proceed(request)

        val credential = credentials() ?: return chain.proceed(request)
        if (!isSameOrigin(credential.serverUrl, request.url)) return chain.proceed(request)

        return chain.proceed(request.newBuilder().header("Authorization", credential.header).build())
    }
}

/**
 * Origin comparison in the RFC 6454 sense: scheme, host and (effective) port must all match.
 *
 * `HttpUrl.port` is already the scheme default when the URL omits it, so `https://host` and
 * `https://host:443` compare equal, while `https://host` and `http://host` do not.
 */
internal fun isSameOrigin(serverUrl: String, url: HttpUrl): Boolean {
    val server = serverUrl.toHttpUrlOrNull() ?: return false
    return server.scheme == url.scheme &&
        server.host.equals(url.host, ignoreCase = true) &&
        server.port == url.port
}

package dk.perspektiva.ttsroad.desktop.data

import com.squareup.moshi.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** `GET /api/mobile/me` — the account as the server sees it right now. */
data class CurrentUserResponse(val user: MobileUser)

/** `GET /api/mobile/devices`. */
data class DevicesResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val devices: List<DeviceSession> = emptyList(),
)

/**
 * One sign-in on this account — a row of the server's `MobileApiToken` table.
 *
 * Everything but [id] is optional on purpose: `last_ip` is null until the session is actually used,
 * and a server that omits a field (or sends one this build has never heard of) must still produce a
 * listable row rather than failing the whole response. That is also why the timestamps stay
 * `String` here and are parsed at the edge — one unreadable date costs that one line, not the list.
 */
data class DeviceSession(
    val id: Int = 0,
    @param:Json(name = "device_name") val deviceName: String? = null,
    @param:Json(name = "created_at") val createdAt: String? = null,
    @param:Json(name = "last_used_at") val lastUsedAt: String? = null,
    @param:Json(name = "expires_at") val expiresAt: String? = null,
    @param:Json(name = "last_ip") val lastIp: String? = null,
    /** `active` | `revoked` | `expired`, computed server-side. Null on a server that omits it. */
    val status: String? = null,
    @param:Json(name = "is_current") val isCurrent: Boolean = false,
) {
    /** Never blank, so a nameless session still has something to read and click. */
    val resolvedName: String
        get() = deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: "Unnamed device"

    /**
     * Whether this row is the window the user is looking at.
     *
     * The server marks it, but only from the token that made the request — a listing fetched over a
     * session cookie, or a backend that omits the flag, would mark nothing at all. The `device_id`
     * kept from the login response is the local second opinion, and getting this wrong is the one
     * mistake that matters here: an unmarked current session is a session the user can revoke by
     * accident from its own row.
     */
    fun isCurrentFor(session: SessionState): Boolean =
        isCurrent || (session.deviceId != null && session.deviceId == id)
}

/**
 * The current session first, then the rest most-recently-used first.
 *
 * The server already orders by `last_used_at DESC`, but that ordering says nothing about which row
 * is *this* device, and "the one you are holding" is the row a user looks for first.
 */
fun List<DeviceSession>.currentSessionFirst(session: SessionState): List<DeviceSession> =
    sortedWith(
        compareByDescending<DeviceSession> { it.isCurrentFor(session) }
            .thenByDescending { parseServerInstant(it.lastUsedAt)?.toEpochMilli() ?: Long.MIN_VALUE }
            .thenByDescending { it.id },
    )

/**
 * Reads a timestamp the backend produced.
 *
 * The server is not consistent about the zone suffix — `app/routers/mobile.py` emits
 * `...123456Z` while `app/services/mobile_auth.py` emits second-precision `...Z`, and other paths
 * serialize naive values — so `Z`, an explicit offset, and a bare local date-time are all tried.
 * Naive input is read as UTC, which is what the database stores. Returns null rather than throwing:
 * a device row with one unreadable date is still worth showing.
 */
fun parseServerInstant(iso: String?): Instant? {
    val text = iso?.trim().orEmpty()
    if (text.isEmpty()) return null
    return runCatching { Instant.parse(text) }
        .recoverCatching { OffsetDateTime.parse(text).toInstant() }
        .recoverCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }
        .getOrNull()
}

private const val TimestampPattern = "d MMM yyyy, HH:mm"

/**
 * A server timestamp as a short local date and time, or null when there is nothing usable.
 *
 * Rendered in the desktop's own zone and locale: "last used" only means something measured against
 * the clock the user is looking at. [zone] and [locale] are parameters so the formatting can be
 * asserted without the test depending on the machine's settings.
 */
fun formatServerTimestamp(
    iso: String?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String? {
    val instant = parseServerInstant(iso) ?: return null
    return DateTimeFormatter.ofPattern(TimestampPattern, locale).withZone(zone).format(instant)
}

/**
 * How much life a session has left, in whole days.
 *
 * Deliberately coarse: tokens last 90 days and are renewed by every authenticated request, so the
 * exact hour is noise. The only question worth answering is whether a session is about to lapse.
 */
fun formatExpiresIn(iso: String?, nowMs: Long): String? {
    val expiry = parseServerInstant(iso) ?: return null
    val remainingMs = expiry.toEpochMilli() - nowMs
    if (remainingMs <= 0L) return "expired"
    return when (val days = remainingMs / 86_400_000L) {
        0L -> "expires today"
        1L -> "expires in 1 day"
        else -> "expires in $days days"
    }
}

package dk.perspektiva.ttsroad.desktop.data

import dk.perspektiva.ttsroad.desktop.ParsedFixtures
import dk.perspektiva.ttsroad.desktop.ServerFixtures
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The device-session model and its timestamps.
 *
 * Timestamps get this much attention because the backend emits at least three shapes for the same
 * kind of field — microsecond `Z` from `app/routers/mobile.py`, second-precision `Z` from
 * `app/services/mobile_auth.py`, and naive values from other paths — and because a device list is
 * exactly the screen where a `DateTimeParseException` would take out the whole page.
 */
class DeviceSessionsTest {

    // --- parseServerInstant ------------------------------------------------------------------

    @Test
    fun `a Z-suffixed UTC timestamp parses`() {
        assertEquals(Instant.parse("2026-10-26T12:00:00Z"), parseServerInstant("2026-10-26T12:00:00Z"))
    }

    @Test
    fun `microsecond precision parses — that is what the login response sends`() {
        assertEquals(
            Instant.parse("2026-11-04T09:12:33.123456Z"),
            parseServerInstant("2026-11-04T09:12:33.123456Z"),
        )
    }

    @Test
    fun `a numeric offset parses and is converted to UTC`() {
        assertEquals(
            Instant.parse("2026-10-26T12:30:00Z"),
            parseServerInstant("2026-10-26T14:30:00+02:00"),
        )
    }

    @Test
    fun `a naive timestamp is read as UTC, which is what the database stores`() {
        assertEquals(Instant.parse("2026-10-26T12:00:00Z"), parseServerInstant("2026-10-26T12:00:00"))
    }

    @Test
    fun `unusable input yields null instead of throwing`() {
        listOf(null, "", "   ", "yesterday", "2026-13-45T99:99:99Z", "0").forEach {
            assertNull(parseServerInstant(it), "expected null for ${it.orEmpty()}")
        }
    }

    // --- formatServerTimestamp ---------------------------------------------------------------

    @Test
    fun `a timestamp renders in the requested zone, not in UTC`() {
        // The acceptance criterion is "local timezone": the same instant must read differently in
        // Copenhagen than in UTC, or the formatting is not doing its job.
        val utc = formatServerTimestamp("2026-10-26T12:00:00Z", ZoneId.of("UTC"), Locale.UK)
        val copenhagen = formatServerTimestamp("2026-10-26T12:00:00Z", ZoneId.of("Europe/Copenhagen"), Locale.UK)

        assertEquals("26 Oct 2026, 12:00", utc)
        assertEquals("26 Oct 2026, 13:00", copenhagen)
    }

    @Test
    fun `an unreadable timestamp formats to null so the row can show a dash`() {
        assertNull(formatServerTimestamp("not a date"))
        assertNull(formatServerTimestamp(null))
    }

    // --- formatExpiresIn ---------------------------------------------------------------------

    @Test
    fun `expiry is described in whole days`() {
        val now = Instant.parse("2026-08-06T09:00:00Z").toEpochMilli()

        assertEquals("expires in 42 days", formatExpiresIn("2026-09-17T09:00:00Z", now))
        assertEquals("expires in 1 day", formatExpiresIn("2026-08-07T10:00:00Z", now))
        assertEquals("expires today", formatExpiresIn("2026-08-06T20:00:00Z", now))
        assertEquals("expired", formatExpiresIn("2026-08-05T09:00:00Z", now))
        assertNull(formatExpiresIn(null, now))
    }

    // --- the model ---------------------------------------------------------------------------

    @Test
    fun `the devices payload decodes, including the null-heavy revoked row`() {
        val devices = ParsedFixtures.devices

        assertEquals(3, devices.size)
        assertEquals("workstation · Windows 11", devices[0].resolvedName)
        assertTrue(devices[0].isCurrent)
        assertEquals("192.168.1.20", devices[0].lastIp)
        assertEquals("revoked", devices[2].status)
        assertNull(devices[2].lastIp)
        assertNull(devices[2].lastUsedAt)
    }

    @Test
    fun `a nameless session still has something to read`() {
        assertEquals("Unnamed device", DeviceSession(id = 1).resolvedName)
        assertEquals("Unnamed device", DeviceSession(id = 1, deviceName = "   ").resolvedName)
    }

    @Test
    fun `a row with almost nothing in it, and an unparseable date, still lists`() {
        val devices = ParsedFixtures.devicesFrom(ServerFixtures.DEVICES_MALFORMED_ROW)

        assertEquals(1, devices.size)
        assertEquals(51, devices[0].id)
        assertEquals("Unnamed device", devices[0].resolvedName)
        // The bad values cost their own fields and nothing else.
        assertNull(formatServerTimestamp(devices[0].lastUsedAt))
        assertNull(formatExpiresIn(devices[0].expiresAt, System.currentTimeMillis()))
        assertNull(devices[0].status)
    }

    @Test
    fun `the stored device id identifies this session when the server does not mark it`() {
        val row = DeviceSession(id = 42, isCurrent = false)

        assertTrue(row.isCurrentFor(SessionState(deviceId = 42)))
        assertFalse(row.isCurrentFor(SessionState(deviceId = 43)))
        assertFalse(row.isCurrentFor(SessionState(deviceId = null)))
        // ...and the server's own flag is still honoured on its own.
        assertTrue(DeviceSession(id = 42, isCurrent = true).isCurrentFor(SessionState(deviceId = null)))
    }

    @Test
    fun `the current session sorts first, then most recently used`() {
        val devices = ParsedFixtures.devices

        val ordered = devices.currentSessionFirst(SessionState(deviceId = 42))

        // 42 leads because it is this session; 43 beats 44 because 44 has never been used.
        assertEquals(listOf(42, 43, 44), ordered.map { it.id })
    }

    @Test
    fun `a session the server did not mark still leads once the stored id recognises it`() {
        val devices = listOf(
            DeviceSession(id = 7, lastUsedAt = "2026-08-06T09:00:00Z"),
            DeviceSession(id = 9, lastUsedAt = "2026-08-01T09:00:00Z"),
        )

        val ordered = devices.currentSessionFirst(SessionState(deviceId = 9))

        assertEquals(listOf(9, 7), ordered.map { it.id })
    }

    @Test
    fun `a session with no last-used date sorts last rather than crashing the comparator`() {
        val ordered = listOf(
            DeviceSession(id = 1, lastUsedAt = null),
            DeviceSession(id = 2, lastUsedAt = "2026-08-05T21:04:00Z"),
        ).currentSessionFirst(SessionState())

        assertEquals(listOf(2, 1), ordered.map { it.id })
    }
}

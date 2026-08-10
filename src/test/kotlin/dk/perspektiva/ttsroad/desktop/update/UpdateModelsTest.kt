package dk.perspektiva.ttsroad.desktop.update

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Version ordering, platform/architecture asset selection and checksum parsing — the three pure
 * decisions that stand between a release feed and a file the user is invited to install.
 */
class UpdateModelsTest {

    // --- Version comparison ---------------------------------------------------------------------

    @Test
    fun `a higher patch, minor or major is newer`() {
        assertTrue(isNewerVersion("1.0.2", "1.0.1"))
        assertTrue(isNewerVersion("1.1.0", "1.0.9"))
        assertTrue(isNewerVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun `the installed version is not newer than itself`() {
        assertFalse(isNewerVersion("1.0.1", "1.0.1"))
        assertFalse(isNewerVersion("1.0.0", "1.0.1"))
    }

    @Test
    fun `a v prefix on the tag does not make it a different version`() {
        assertFalse(isNewerVersion("v1.0.1", "1.0.1"))
        assertTrue(isNewerVersion("v1.0.2", "1.0.1"))
    }

    @Test
    fun `versions of different lengths compare on the parts they share`() {
        assertFalse(isNewerVersion("1.0", "1.0.0"))
        assertTrue(isNewerVersion("1.0.1", "1.0"))
    }

    @Test
    fun `a pre-release sorts below the release with the same core`() {
        // Otherwise an installed 1.1.0 would be offered "1.1.0-rc1" as an upgrade.
        assertFalse(isNewerVersion("1.1.0-rc1", "1.1.0"))
        assertTrue(isNewerVersion("1.1.0", "1.1.0-rc1"))
    }

    @Test
    fun `an unparseable version is treated as older, never as newer`() {
        // The safe direction: garbage in the feed must not trigger a download offer.
        assertFalse(isNewerVersion("not-a-version", "1.0.1"))
    }

    // --- Asset selection ------------------------------------------------------------------------

    private val assets = listOf(
        ReleaseAsset("ttsroad_1.0.2-1_amd64.deb", "https://example.invalid/deb", 100),
        ReleaseAsset("TTSRoad-1.0.2.msi", "https://example.invalid/msi", 200),
        ReleaseAsset("TTSRoad-1.0.2.dmg", "https://example.invalid/dmg", 300),
        ReleaseAsset("SHA256SUMS", "https://example.invalid/sums", 10),
    )

    @Test
    fun `each platform selects its own installer format`() {
        assertEquals("ttsroad_1.0.2-1_amd64.deb", selectAssetFor(assets, "Linux", "amd64")?.name)
        assertEquals("TTSRoad-1.0.2.msi", selectAssetFor(assets, "Windows 11", "amd64")?.name)
        assertEquals("TTSRoad-1.0.2.dmg", selectAssetFor(assets, "Mac OS X", "aarch64")?.name)
    }

    @Test
    fun `x86_64 and x64 name the same Linux architecture as amd64`() {
        assertEquals("ttsroad_1.0.2-1_amd64.deb", selectAssetFor(assets, "Linux", "x86_64")?.name)
        assertEquals("ttsroad_1.0.2-1_amd64.deb", selectAssetFor(assets, "Linux", "x64")?.name)
    }

    @Test
    fun `an architecture the release does not publish for selects nothing`() {
        // dpkg would refuse an amd64 package here, so offering it would be a download that cannot
        // succeed. Nothing is the correct answer.
        assertNull(selectAssetFor(assets, "Linux", "aarch64"))
    }

    @Test
    fun `a release without this platform's format selects nothing`() {
        val linuxOnly = assets.filter { it.name.endsWith(".deb") }
        assertNull(selectAssetFor(linuxOnly, "Windows 11", "amd64"))
    }

    // --- Checksums ------------------------------------------------------------------------------

    private val digest = "a".repeat(64)

    @Test
    fun `both sha256sum spellings and the leading dot-slash are understood`() {
        val parsed = parseChecksums(
            """
            $digest  ./ttsroad_1.0.2-1_amd64.deb
            ${"b".repeat(64)} *TTSRoad-1.0.2.msi
            """.trimIndent(),
        )
        assertEquals(digest, parsed["ttsroad_1.0.2-1_amd64.deb"])
        assertEquals("b".repeat(64), parsed["TTSRoad-1.0.2.msi"])
    }

    @Test
    fun `a malformed line is skipped rather than failing the whole file`() {
        val parsed = parseChecksums(
            """
            this is not a checksum line
            deadbeef  short-digest.deb
            $digest  good.deb
            """.trimIndent(),
        )
        assertEquals(mapOf("good.deb" to digest), parsed)
    }

    @Test
    fun `an uppercase digest matches the lowercase one computed locally`() {
        assertEquals(digest, parseChecksums("${digest.uppercase()}  good.deb")["good.deb"])
    }
}

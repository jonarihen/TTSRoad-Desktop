package dk.perspektiva.ttsroad.desktop.data

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The three storage roots, asserted from any platform.
 *
 * The split that matters: downloads the user asked for go under the *data* root, which no cache
 * cleaner may empty, and rebuildable bytes go under the *cache* root, which anything may empty.
 * Getting that backwards produces an "Offline" badge with no bytes behind it.
 */
class AppDirectoriesTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    // --- Linux / XDG ----------------------------------------------------------------------------

    @Test
    fun `linux honours the XDG base directories`() {
        val e = env(
            "XDG_CONFIG_HOME" to "/xdg/config",
            "XDG_DATA_HOME" to "/xdg/data",
            "XDG_CACHE_HOME" to "/xdg/cache",
            "XDG_STATE_HOME" to "/xdg/state",
        )
        assertEquals("/xdg/config/TTSRoad", AppDirectories.configDir("Linux", "/home/u", e).path)
        assertEquals("/xdg/data/TTSRoad", AppDirectories.dataDir("Linux", "/home/u", e).path)
        assertEquals("/xdg/cache/TTSRoad", AppDirectories.cacheDir("Linux", "/home/u", e).path)
        assertEquals("/xdg/state/TTSRoad", AppDirectories.logDir("Linux", "/home/u", e).path)
    }

    @Test
    fun `linux falls back to the spec's defaults`() {
        val e = env()
        assertEquals("/home/u/.config/TTSRoad", AppDirectories.configDir("Linux", "/home/u", e).path)
        assertEquals("/home/u/.local/share/TTSRoad", AppDirectories.dataDir("Linux", "/home/u", e).path)
        assertEquals("/home/u/.cache/TTSRoad", AppDirectories.cacheDir("Linux", "/home/u", e).path)
        assertEquals("/home/u/.local/state/TTSRoad", AppDirectories.logDir("Linux", "/home/u", e).path)
    }

    @Test
    fun `a relative XDG value is ignored rather than resolved against the cwd`() {
        // The spec says so, and the alternative scatters a user's downloads into whatever directory
        // the app was launched from.
        val e = env(
            "XDG_DATA_HOME" to "relative/path",
            "XDG_CACHE_HOME" to "also/relative",
            "XDG_STATE_HOME" to "state/relative",
        )
        assertEquals("/home/u/.local/share/TTSRoad", AppDirectories.dataDir("Linux", "/home/u", e).path)
        assertEquals("/home/u/.cache/TTSRoad", AppDirectories.cacheDir("Linux", "/home/u", e).path)
        assertEquals("/home/u/.local/state/TTSRoad", AppDirectories.logDir("Linux", "/home/u", e).path)
    }

    @Test
    fun `an empty XDG value is treated as unset`() {
        val e = env("XDG_DATA_HOME" to "")
        assertEquals("/home/u/.local/share/TTSRoad", AppDirectories.dataDir("Linux", "/home/u", e).path)
    }

    // --- Windows --------------------------------------------------------------------------------

    @Test
    fun `windows keeps downloads out of the roaming profile`() {
        // A roaming profile is copied to a domain server at sign-in. An audiobook library is
        // exactly what must not be dragged across a network by a login script.
        val e = env("APPDATA" to "C:\\Users\\u\\AppData\\Roaming", "LOCALAPPDATA" to "C:\\Users\\u\\AppData\\Local")
        val config = AppDirectories.configDir("Windows 11", "C:\\Users\\u", e).path
        val data = AppDirectories.dataDir("Windows 11", "C:\\Users\\u", e).path
        val logs = AppDirectories.logDir("Windows 11", "C:\\Users\\u", e).path

        assertTrue(config.contains("Roaming"), config)
        assertTrue(data.contains("Local") && !data.contains("Roaming"), data)
        assertTrue(logs.contains("Local") && logs.endsWith("Logs"), logs)
    }

    @Test
    fun `windows falls back when the environment is missing`() {
        val data = AppDirectories.dataDir("Windows 11", "C:\\Users\\u", env()).path
        assertTrue(data.contains("AppData"), data)
        assertTrue(data.endsWith("TTSRoad"), data)
    }

    // --- macOS ----------------------------------------------------------------------------------

    @Test
    fun `macos uses Caches for the cache root and Application Support for the rest`() {
        val e = env()
        assertTrue(AppDirectories.cacheDir("Mac OS X", "/Users/u", e).path.contains("Library/Caches"))
        assertTrue(AppDirectories.dataDir("Mac OS X", "/Users/u", e).path.contains("Application Support"))
        assertEquals("/Users/u/Library/Logs/TTSRoad", AppDirectories.logDir("Mac OS X", "/Users/u", e).path)
    }

    // --- The invariant the whole split exists for -----------------------------------------------

    @Test
    fun `data and cache are never the same directory on any platform`() {
        val platforms = listOf("Linux", "Windows 11", "Mac OS X")
        for (os in platforms) {
            val data = AppDirectories.dataDir(os, "/home/u", env())
            val cache = AppDirectories.cacheDir(os, "/home/u", env())
            assertNotEquals(data.path, cache.path, "data and cache collided on $os")
        }
    }

    @Test
    fun `the session store resolves through the same rules`() {
        val e = env("XDG_CONFIG_HOME" to "/xdg/config")
        assertEquals(
            AppDirectories.configDir("Linux", "/home/u", e).path,
            FileSessionStore.configDir("Linux", "/home/u", e).path,
        )
    }
}

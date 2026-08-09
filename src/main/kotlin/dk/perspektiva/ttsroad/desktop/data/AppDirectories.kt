package dk.perspektiva.ttsroad.desktop.data

import java.io.File

/**
 * Where this app is allowed to write, split by what the operating system is entitled to do with it.
 *
 * The split is the whole point and it is not cosmetic:
 *
 * - **Config** is small, hand-made state — the server, the window placement, the listening
 *   preferences. Losing it is annoying.
 * - **Data** is what the *user asked for*: downloaded chapters. A cleaner is entitled to empty a
 *   cache directory without asking, so a download parked in one would evaporate on a machine with
 *   an aggressive janitor — and the user would find an "Offline" badge with no bytes behind it.
 * - **Cache** is everything the app can rebuild by asking the server again: streamed audio it chose
 *   to retain, HTTP metadata. Deleting it costs bandwidth and nothing else.
 *
 * [osName], [userHome] and [env] are parameters throughout so the path rules — in particular the
 * XDG Base Directory spec, where honouring `$XDG_DATA_HOME` is the difference between respecting a
 * user's layout and ignoring it — can be tested from any platform.
 */
object AppDirectories {

    /** The directory name this app owns inside each platform base. */
    const val AppFolder: String = "TTSRoad"

    /**
     * Small, hand-made state: `session.json`, `playback.json`, `history.json`, window placement.
     *
     * `%APPDATA%/TTSRoad`, `~/Library/Application Support/TTSRoad`, or `$XDG_CONFIG_HOME/TTSRoad`.
     */
    fun configDir(
        osName: String = System.getProperty("os.name").orEmpty(),
        userHome: String = System.getProperty("user.home").orEmpty(),
        env: (String) -> String? = System::getenv,
    ): File = when (platformOf(osName)) {
        Platform.Windows -> File(envDir(env, "APPDATA") ?: File(userHome, "AppData/Roaming"), AppFolder)
        Platform.MacOs -> File(File(userHome, "Library/Application Support"), AppFolder)
        Platform.Linux -> File(xdgDir(env, "XDG_CONFIG_HOME", userHome, ".config"), AppFolder)
    }

    /**
     * User-requested downloads. **Never** evicted by this app or by the platform's cache cleaner.
     *
     * On Windows this is `%LOCALAPPDATA%`, not `%APPDATA%`: a roaming profile copies its contents
     * to a domain server at every sign-in, and an audiobook library is precisely the thing that
     * must not be dragged across a network by a login script.
     */
    fun dataDir(
        osName: String = System.getProperty("os.name").orEmpty(),
        userHome: String = System.getProperty("user.home").orEmpty(),
        env: (String) -> String? = System::getenv,
    ): File = when (platformOf(osName)) {
        Platform.Windows -> File(envDir(env, "LOCALAPPDATA") ?: File(userHome, "AppData/Local"), AppFolder)
        Platform.MacOs -> File(File(userHome, "Library/Application Support"), AppFolder)
        Platform.Linux -> File(xdgDir(env, "XDG_DATA_HOME", userHome, ".local/share"), AppFolder)
    }

    /**
     * Rebuildable bytes. Anything here may be deleted at any time by the app, the user, or the OS.
     *
     * Nothing may live here that the app would later report as "Offline" — see [dataDir].
     */
    fun cacheDir(
        osName: String = System.getProperty("os.name").orEmpty(),
        userHome: String = System.getProperty("user.home").orEmpty(),
        env: (String) -> String? = System::getenv,
    ): File = when (platformOf(osName)) {
        // Windows has no cache base of its own; the convention is a Cache folder under LocalAppData.
        Platform.Windows ->
            File(File(envDir(env, "LOCALAPPDATA") ?: File(userHome, "AppData/Local"), AppFolder), "Cache")

        Platform.MacOs -> File(File(userHome, "Library/Caches"), AppFolder)
        Platform.Linux -> File(xdgDir(env, "XDG_CACHE_HOME", userHome, ".cache"), AppFolder)
    }

    private enum class Platform { Windows, MacOs, Linux }

    private fun platformOf(osName: String): Platform {
        val os = osName.lowercase()
        return when {
            os.contains("win") -> Platform.Windows
            os.contains("mac") || os.contains("darwin") -> Platform.MacOs
            else -> Platform.Linux
        }
    }

    private fun envDir(env: (String) -> String?, name: String): File? =
        env(name)?.takeIf { it.isNotBlank() }?.let { File(it) }

    /**
     * An XDG base directory, honouring the spec's "ignore a relative value" rule.
     *
     * The spec is explicit that a non-absolute `$XDG_*_HOME` must be treated as unset rather than
     * resolved against the working directory — otherwise a stray `XDG_DATA_HOME=tmp` would scatter
     * a user's downloads into whatever directory the app happened to be launched from.
     */
    private fun xdgDir(env: (String) -> String?, name: String, userHome: String, fallback: String): File =
        env(name)?.takeIf { it.isNotBlank() && it.startsWith("/") }?.let { File(it) }
            ?: File(userHome, fallback)
}

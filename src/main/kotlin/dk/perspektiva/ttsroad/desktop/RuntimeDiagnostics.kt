package dk.perspektiva.ttsroad.desktop

import dk.perspektiva.ttsroad.desktop.data.AppDirectories
import dk.perspektiva.ttsroad.desktop.data.redactSecrets
import dk.perspektiva.ttsroad.desktop.player.GstPlaybackEngine
import java.io.File

const val VersionFlag: String = "--version"
const val DiagnosticsFlag: String = "--diagnostics"

fun versionText(): String = "${BuildInfo.APP_NAME} ${BuildInfo.VERSION}"

/**
 * Safe, side-effect-free launcher diagnostics. It never opens the session store or credential
 * helper, so running it cannot read a token, alter a keyring entry, or require a graphical session.
 */
fun buildRuntimeDiagnostics(
    osName: String = System.getProperty("os.name").orEmpty(),
    osVersion: String = System.getProperty("os.version").orEmpty(),
    architecture: String = System.getProperty("os.arch").orEmpty(),
    javaVm: String = System.getProperty("java.vm.name").orEmpty(),
    javaVersion: String = System.getProperty("java.version").orEmpty(),
    userHome: String = System.getProperty("user.home").orEmpty(),
    appPath: String? = System.getProperty("jpackage.app-path"),
    env: (String) -> String? = System::getenv,
    commandAvailable: (String, (String) -> String?) -> Boolean = ::isCommandAvailable,
    gstreamerAvailable: () -> Boolean = GstPlaybackEngine::isAvailable,
    modulePresent: (String) -> Boolean = { ModuleLayer.boot().findModule(it).isPresent },
): String = redactSecrets(
    buildString {
        appendLine(versionText())
        appendLine("Debian package version: ${BuildInfo.VERSION}-${BuildInfo.DEB_REVISION}")
        appendLine("OS: $osName $osVersion ($architecture)")
        appendLine("Java: $javaVm $javaVersion")
        appendLine("Bundled runtime: ${if (appPath.isNullOrBlank()) "no/unknown" else "yes"}")
        appendLine("Config: ${AppDirectories.configDir(osName, userHome, env)}")
        appendLine("Data: ${AppDirectories.dataDir(osName, userHome, env)}")
        appendLine("Cache: ${AppDirectories.cacheDir(osName, userHome, env)}")
        appendLine("Log: ${File(AppDirectories.logDir(osName, userHome, env), "ttsroad.log")}")
        appendLine("GStreamer backend: ${if (gstreamerAvailable()) "available" else "unavailable (Java Sound fallback)"}")
        appendLine("Secret Service helper: ${if (commandAvailable("secret-tool", env)) "available" else "unavailable"}")
        appendLine("D-Bus session: ${if (env("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) "unavailable" else "available"}")
        appendLine("Accessibility module: ${if (modulePresent("jdk.accessibility")) "present" else "missing"}")
        append("MPRIS auth module: ${if (modulePresent("jdk.security.auth")) "present" else "missing"}")
    },
)

fun isCommandAvailable(name: String, env: (String) -> String? = System::getenv): Boolean {
    if (name.isBlank() || name.contains('/') || name.contains('\\')) return false
    return env("PATH")
        .orEmpty()
        .split(File.pathSeparatorChar)
        .asSequence()
        .filter { it.isNotBlank() }
        .map { File(it, name) }
        .any { it.isFile && it.canExecute() }
}

package dk.perspektiva.ttsroad.desktop

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RuntimeDiagnosticsTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `version output is the generated application version`() {
        assertTrue(versionText().startsWith("TTSRoad "), versionText())
        assertTrue(versionText().endsWith(BuildInfo.VERSION), versionText())
    }

    @Test
    fun `launcher diagnostics are useful and never carry credentials`() {
        val token = "ttsr_Zm9vYmFyYmF6cXV1eA"
        val values = mapOf(
            "PATH" to "/usr/bin",
            "XDG_CONFIG_HOME" to "/tmp/$token/config",
            "XDG_DATA_HOME" to "/tmp/data",
            "XDG_CACHE_HOME" to "/tmp/cache",
            "XDG_STATE_HOME" to "/tmp/state",
            "DBUS_SESSION_BUS_ADDRESS" to "unix:path=/run/user/1000/bus?token=$token",
        )
        val text = buildRuntimeDiagnostics(
            osName = "Linux",
            osVersion = "test",
            architecture = "amd64",
            javaVm = "Test VM",
            javaVersion = "25",
            userHome = "/home/test",
            appPath = "/opt/TTSRoad/bin/TTSRoad",
            env = values::get,
            commandAvailable = { name, _ -> name == "secret-tool" },
            gstreamerAvailable = { true },
            modulePresent = { true },
        )

        assertFalse(text.contains(token), text)
        assertTrue(text.contains("Debian package version: ${BuildInfo.VERSION}-${BuildInfo.DEB_REVISION}"), text)
        assertTrue(text.contains("Bundled runtime: yes"), text)
        assertTrue(text.contains("GStreamer backend: available"), text)
        assertTrue(text.contains("Secret Service helper: available"), text)
        assertTrue(text.contains("Accessibility module: present"), text)
        assertTrue(text.contains("/tmp/state/TTSRoad/ttsroad.log"), text)
    }

    @Test
    fun `command lookup accepts only an executable file on PATH`() {
        val bin = File(tempDir, "bin").apply { mkdirs() }
        val helper = File(bin, "secret-tool").apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
        val env: (String) -> String? = { if (it == "PATH") bin.absolutePath else null }

        assertTrue(helper.canExecute())
        assertTrue(isCommandAvailable("secret-tool", env))
        assertFalse(isCommandAvailable("missing", env))
        assertFalse(isCommandAvailable("../secret-tool", env))
    }
}

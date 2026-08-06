package dk.perspektiva.ttsroad.desktop.security

import dk.perspektiva.ttsroad.desktop.data.AppLog
import java.util.concurrent.TimeUnit

/** Result of running a helper process. */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    companion object {
        /** The executable itself could not be started (not installed, not on PATH). */
        val NotExecutable: CommandResult = CommandResult(exitCode = 127, stdout = "", stderr = "not executable")
    }
}

/**
 * Seam for "run this helper and give me its output".
 *
 * Both the Linux and the macOS credential stores drive an OS-provided command-line client rather
 * than speaking a wire protocol themselves, so this interface is the whole test surface for them:
 * a fake runner lets the store logic — argument shapes, exit-code mapping, and above all *the
 * secret going over stdin and never into argv* — be asserted on any platform.
 */
fun interface CommandRunner {
    /** Runs [command], optionally feeding [stdin], and waits for it to exit. Never throws. */
    fun run(command: List<String>, stdin: String?): CommandResult
}

/** Real [CommandRunner]. Bounded by a timeout so a keyring prompt cannot hang the app forever. */
class ProcessCommandRunner(
    private val timeoutSeconds: Long = 20,
) : CommandRunner {
    override fun run(command: List<String>, stdin: String?): CommandResult = try {
        val process = ProcessBuilder(command).start()
        // Both pipes are drained on their own threads: reading one to EOF before the other
        // deadlocks as soon as the helper fills the pipe we are not reading.
        val out = StringBuilder()
        val err = StringBuilder()
        val outReader = drain(process.inputStream, out)
        val errReader = drain(process.errorStream, err)
        process.outputStream.use { sink ->
            if (stdin != null) sink.write(stdin.toByteArray(Charsets.UTF_8))
        }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        outReader.join(1_000)
        errReader.join(1_000)
        if (!finished) {
            CommandResult(exitCode = -1, stdout = out.toString(), stderr = "timed out after ${timeoutSeconds}s")
        } else {
            CommandResult(process.exitValue(), out.toString(), err.toString())
        }
    } catch (e: Exception) {
        // An absent executable arrives as IOException; that is "no keyring here", not a crash.
        AppLog.warn("credential helper ${command.firstOrNull()} could not be started", e)
        CommandResult.NotExecutable
    }

    private fun drain(stream: java.io.InputStream, into: StringBuilder): Thread =
        Thread { runCatching { into.append(stream.bufferedReader().readText()) } }
            .apply { isDaemon = true; start() }
}

/**
 * freedesktop.org Secret Service (GNOME Keyring, KWallet's Secret Service bridge, KeePassXC, …)
 * via libsecret's `secret-tool`.
 *
 * **Why the CLI and not D-Bus directly.** The Secret Service API is a D-Bus interface, and using
 * it properly means an authenticated D-Bus session connection, the `org.freedesktop.Secret.Session`
 * handshake (including the DH key-exchange algorithm when the transport is not trusted), collection
 * unlocking, and prompt handling. There is no maintained pure-JVM D-Bus client I could verify
 * resolves from Maven Central for this build, and hand-rolling the wire protocol for a credential
 * path is exactly the kind of code that fails open. `secret-tool` ships with libsecret, which is a
 * hard dependency of GNOME Keyring and is present on essentially every desktop Linux install; it
 * performs the full session/unlock/prompt dance and is maintained by the same project that defines
 * the API. The secret is passed on **stdin**, never as an argument, so it never appears in the
 * process table (`/proc/<pid>/cmdline`).
 *
 * If the tool is missing, [createOrNull] returns null and the app falls back to a session-only
 * store with a visible explanation rather than to a file.
 */
class SecretServiceCredentialStore internal constructor(
    private val runner: CommandRunner,
    private val executable: String = "secret-tool",
    private val service: String = "dk.perspektiva.ttsroad.desktop",
) : CredentialStore {

    override val id: String get() = "secret-service"
    override val displayName: String get() = "Secret Service keyring"
    override val persistsAcrossRestarts: Boolean get() = true

    override fun store(key: String, secret: String) {
        val result = runner.run(
            listOf(executable, "store", "--label=TTSRoad session", "service", service, "account", key),
            stdin = secret,
        )
        if (result.exitCode != 0) {
            throw CredentialStoreException("The keyring refused to store the session (secret-tool exit ${result.exitCode})")
        }
    }

    override fun retrieve(key: String): String? {
        val result = runner.run(lookupCommand(key), stdin = null)
        if (result.exitCode != 0) return null
        // `secret-tool lookup` writes the secret raw; some builds add a trailing newline.
        return result.stdout.removeSuffix("\n").takeIf { it.isNotEmpty() }
    }

    override fun delete(key: String) {
        runner.run(listOf(executable, "clear", "service", service, "account", key), stdin = null)
    }

    private fun lookupCommand(key: String) =
        listOf(executable, "lookup", "service", service, "account", key)

    companion object {
        /**
         * Probes the keyring with a lookup for an attribute nothing ever stores.
         *
         * `secret-tool` exits 1 both for "no such secret" and for "no Secret Service on the bus",
         * and only the second writes to stderr — which is the distinction that matters, because a
         * headless box with libsecret installed but no daemon must not be treated as a keyring.
         */
        fun createOrNull(runner: CommandRunner): SecretServiceCredentialStore? {
            val store = SecretServiceCredentialStore(runner)
            val probe = runner.run(store.lookupCommand("availability-probe"), stdin = null)
            val available = probe.exitCode == 0 || (probe.exitCode == 1 && probe.stderr.isBlank())
            if (!available) {
                AppLog.warn("no usable Secret Service keyring (secret-tool exit ${probe.exitCode})")
                return null
            }
            return store
        }
    }
}

/**
 * macOS login keychain via `/usr/bin/security`, the keychain client Apple ships.
 *
 * The password is written to stdin (`-w` with no value makes `security` read it, and prompts a
 * second time to confirm) rather than passed as `-w <secret>`, which would publish the token in
 * the process table.
 *
 * NOTE: this path is written against Apple's documented `security(1)` interface but has **not**
 * been executed on macOS — the build host for this phase is Windows. It is covered by tests
 * against a fake [CommandRunner], which pin the argument shapes and the stdin handling.
 */
class MacKeychainCredentialStore internal constructor(
    private val runner: CommandRunner,
    private val executable: String = "/usr/bin/security",
    private val service: String = "dk.perspektiva.ttsroad.desktop",
) : CredentialStore {

    override val id: String get() = "macos-keychain"
    override val displayName: String get() = "macOS Keychain"
    override val persistsAcrossRestarts: Boolean get() = true

    override fun store(key: String, secret: String) {
        val result = runner.run(
            // -U updates an existing item instead of failing with errSecDuplicateItem.
            listOf(executable, "add-generic-password", "-U", "-a", key, "-s", service, "-w"),
            stdin = "$secret\n$secret\n",
        )
        if (result.exitCode != 0) {
            throw CredentialStoreException("The keychain refused to store the session (security exit ${result.exitCode})")
        }
    }

    override fun retrieve(key: String): String? {
        val result = runner.run(findCommand(key), stdin = null)
        if (result.exitCode != 0) return null
        return result.stdout.removeSuffix("\n").takeIf { it.isNotEmpty() }
    }

    override fun delete(key: String) {
        runner.run(listOf(executable, "delete-generic-password", "-a", key, "-s", service), stdin = null)
    }

    private fun findCommand(key: String) =
        listOf(executable, "find-generic-password", "-a", key, "-s", service, "-w")

    companion object {
        /** Exit 44 is `security`'s "the specified item could not be found in the keychain". */
        private const val ItemNotFound = 44

        fun createOrNull(runner: CommandRunner): MacKeychainCredentialStore? {
            val store = MacKeychainCredentialStore(runner)
            val probe = runner.run(store.findCommand("availability-probe"), stdin = null)
            val available = probe.exitCode == 0 || probe.exitCode == ItemNotFound
            if (!available) {
                AppLog.warn("no usable macOS keychain (security exit ${probe.exitCode})")
                return null
            }
            return store
        }
    }
}

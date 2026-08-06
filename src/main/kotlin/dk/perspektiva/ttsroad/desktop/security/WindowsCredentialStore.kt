package dk.perspektiva.ttsroad.desktop.security

import dk.perspektiva.ttsroad.desktop.data.AppLog
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.Charset

/**
 * Windows Credential Manager, through the Win32 credential API (`Advapi32.dll`).
 *
 * The secret is handed to `CredWriteW` and read back with `CredReadW`, so it is stored by the OS
 * under the user's logon credential (DPAPI-protected, per-user) and never touches a file this app
 * owns or a process argument.
 *
 * **Why the FFM API and not a JNA/JNI dependency.** The app already targets a JDK 25 toolchain,
 * where `java.lang.foreign` is a final API, so the alternative would be adding a native-bridge
 * library to the dependency graph and to the jlink image for four function calls. The trade is
 * that `Linker.downcallHandle`, `SymbolLookup.libraryLookup` and `MemorySegment.reinterpret` are
 * *restricted* methods: they warn on JDK 25 unless the launcher passes
 * `--enable-native-access=ALL-UNNAMED` (which the packaged app and the test JVM both do) and will
 * be denied outright in some future release.
 *
 * Every entry point is defensive: [createOrNull] returns null if anything about the linkage is
 * wrong, and the app then falls back to a session-only store rather than to a file.
 */
class WindowsCredentialStore private constructor(
    private val credWrite: MethodHandle,
    private val credRead: MethodHandle,
    private val credDelete: MethodHandle,
    private val credFree: MethodHandle,
) : CredentialStore {

    override val id: String get() = "windows-credential-manager"
    override val displayName: String get() = "Windows Credential Manager"
    override val persistsAcrossRestarts: Boolean get() = true

    override fun store(key: String, secret: String) {
        Arena.ofConfined().use { arena ->
            val blob = secret.toByteArray(WideCharset)
            val blobSegment = arena.allocate(blob.size.toLong().coerceAtLeast(1L))
            MemorySegment.copy(blob, 0, blobSegment, ValueLayout.JAVA_BYTE, 0, blob.size)

            val credential = arena.allocate(CredentialLayout)
            credential.fill(0.toByte())
            credential.set(ValueLayout.JAVA_INT, OffsetType, CredTypeGeneric)
            credential.set(ValueLayout.ADDRESS, OffsetTargetName, arena.allocateFrom(key, WideCharset))
            credential.set(ValueLayout.JAVA_INT, OffsetBlobSize, blob.size)
            credential.set(ValueLayout.ADDRESS, OffsetBlob, blobSegment)
            credential.set(ValueLayout.JAVA_INT, OffsetPersist, CredPersistLocalMachine)
            credential.set(ValueLayout.ADDRESS, OffsetUserName, arena.allocateFrom(TargetUser, WideCharset))

            val state = arena.allocate(CallStateLayout)
            val ok = credWrite.invokeWithArguments(state, credential, 0) as Int
            if (ok == 0) {
                throw CredentialStoreException(
                    "Windows Credential Manager refused to store the session (error ${lastError(state)})",
                )
            }
        }
    }

    override fun retrieve(key: String): String? = Arena.ofConfined().use { arena ->
        val out = arena.allocate(ValueLayout.ADDRESS)
        val state = arena.allocate(CallStateLayout)
        val ok = credRead.invokeWithArguments(
            state,
            arena.allocateFrom(key, WideCharset),
            CredTypeGeneric,
            0,
            out,
        ) as Int
        if (ok == 0) {
            val error = lastError(state)
            if (error == ErrorNotFound) return null
            AppLog.warn("Windows Credential Manager could not read the stored session (error $error)")
            return null
        }
        val credentialPointer = out.get(ValueLayout.ADDRESS, 0)
        try {
            val credential = credentialPointer.reinterpret(CredentialLayout.byteSize())
            val size = credential.get(ValueLayout.JAVA_INT, OffsetBlobSize)
            if (size <= 0) return null
            val blob = credential.get(ValueLayout.ADDRESS, OffsetBlob).reinterpret(size.toLong())
            String(blob.toArray(ValueLayout.JAVA_BYTE), WideCharset)
        } finally {
            credFree.invokeWithArguments(credentialPointer)
        }
    }

    override fun delete(key: String) {
        Arena.ofConfined().use { arena ->
            val state = arena.allocate(CallStateLayout)
            credDelete.invokeWithArguments(state, arena.allocateFrom(key, WideCharset), CredTypeGeneric, 0)
            // A missing entry (ERROR_NOT_FOUND) is a successful delete as far as callers care.
        }
    }

    private fun lastError(state: MemorySegment): Int = state.get(ValueLayout.JAVA_INT, LastErrorOffset)

    companion object {
        /** Win32 wide strings are UTF-16LE; `allocateFrom` appends the two-byte terminator. */
        private val WideCharset: Charset = Charsets.UTF_16LE

        private const val CredTypeGeneric = 1
        private const val CredPersistLocalMachine = 2
        private const val ErrorNotFound = 1168

        /** Shown as the account name in the Credential Manager UI; not a secret and not used as one. */
        private const val TargetUser = "TTSRoad"

        /**
         * `CREDENTIALW` from `wincred.h`, 64-bit layout. The explicit padding after
         * `CredentialBlobSize` is the compiler's alignment of the following pointer, and getting
         * it wrong would silently read the wrong field rather than fail.
         */
        private val CredentialLayout: MemoryLayout = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("Flags"),
            ValueLayout.JAVA_INT.withName("Type"),
            ValueLayout.ADDRESS.withName("TargetName"),
            ValueLayout.ADDRESS.withName("Comment"),
            ValueLayout.JAVA_INT.withName("LastWrittenLow"),
            ValueLayout.JAVA_INT.withName("LastWrittenHigh"),
            ValueLayout.JAVA_INT.withName("CredentialBlobSize"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("CredentialBlob"),
            ValueLayout.JAVA_INT.withName("Persist"),
            ValueLayout.JAVA_INT.withName("AttributeCount"),
            ValueLayout.ADDRESS.withName("Attributes"),
            ValueLayout.ADDRESS.withName("TargetAlias"),
            ValueLayout.ADDRESS.withName("UserName"),
        )

        private fun offsetOf(name: String): Long =
            CredentialLayout.byteOffset(MemoryLayout.PathElement.groupElement(name))

        private val OffsetType = offsetOf("Type")
        private val OffsetTargetName = offsetOf("TargetName")
        private val OffsetBlobSize = offsetOf("CredentialBlobSize")
        private val OffsetBlob = offsetOf("CredentialBlob")
        private val OffsetPersist = offsetOf("Persist")
        private val OffsetUserName = offsetOf("UserName")

        private val CallStateLayout: MemoryLayout = Linker.Option.captureStateLayout()
        private val LastErrorOffset: Long =
            CallStateLayout.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"))

        /**
         * Links against Advapi32, or returns null if that is not possible on this machine.
         *
         * Catches [Throwable] rather than [Exception] deliberately: a linkage problem surfaces as
         * an `Error`, and a credential store that cannot be created must degrade to "no keyring",
         * never take the app down on startup.
         */
        fun createOrNull(): WindowsCredentialStore? = try {
            val linker = Linker.nativeLinker()
            val advapi = SymbolLookup.libraryLookup("Advapi32.dll", Arena.global())
            val captureLastError = Linker.Option.captureCallState("GetLastError")

            fun link(name: String, descriptor: FunctionDescriptor, capture: Boolean): MethodHandle {
                val address = advapi.find(name).orElseThrow {
                    UnsatisfiedLinkError("Advapi32.dll does not export $name")
                }
                return if (capture) {
                    linker.downcallHandle(address, descriptor, captureLastError)
                } else {
                    linker.downcallHandle(address, descriptor)
                }
            }

            val store = WindowsCredentialStore(
                // BOOL CredWriteW(PCREDENTIALW, DWORD)
                credWrite = link(
                    "CredWriteW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                    capture = true,
                ),
                // BOOL CredReadW(LPCWSTR, DWORD, DWORD, PCREDENTIALW*)
                credRead = link(
                    "CredReadW",
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                    ),
                    capture = true,
                ),
                // BOOL CredDeleteW(LPCWSTR, DWORD, DWORD)
                credDelete = link(
                    "CredDeleteW",
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                    ),
                    capture = true,
                ),
                // void CredFree(PVOID)
                credFree = link(
                    "CredFree",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
                    capture = false,
                ),
            )
            // Prove the linkage actually works before promising the caller a keyring: a wrong
            // struct layout or a blocked restricted method must surface here, not at sign-in.
            store.retrieve("ttsroad/availability-probe")
            store
        } catch (e: Throwable) {
            AppLog.warn("Windows Credential Manager is not usable from this runtime", e)
            null
        }
    }
}

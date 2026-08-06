package dk.perspektiva.ttsroad.desktop.security

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/**
 * File writes for anything that describes a session.
 *
 * Two properties matter and neither is the default:
 *
 * - **Atomic.** The settings file is read at startup and rewritten on every sign-in/out. A
 *   truncating write that is interrupted leaves a half file, which parses to "signed out" and
 *   silently loses the server the user configured. Writing a sibling temp file and renaming it
 *   means a reader sees either the old file or the new one.
 * - **Owner-only.** Even without the token in it, the file names the server and the account. On a
 *   shared machine the default permissions make that world-readable.
 */
object SecureFiles {

    /**
     * Writes [content] to [file] via a temp file in the same directory, then renames it into place.
     *
     * The temp file is permission-restricted *before* anything is written to it, so the content is
     * never briefly readable by other users. Returns false if the owner-only restriction could not
     * be applied — the write still happened, but the caller may want to say so.
     */
    fun writeAtomically(file: File, content: String): Boolean {
        val target = file.toPath()
        val directory = target.parent
        if (directory != null) Files.createDirectories(directory)

        val temp = Files.createTempFile(directory, ".${file.name}", ".tmp")
        val restricted = restrictToOwner(temp)
        return try {
            Files.write(temp, content.toByteArray(Charsets.UTF_8))
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Some network/virtual filesystems cannot rename atomically; a replacing move is
                // still better than truncating the destination in place.
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            restricted && restrictToOwner(target)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    /**
     * Restricts [path] to its owner: `rw-------` on POSIX, a single owner ACE on Windows.
     *
     * Returns false when the filesystem supports neither view (some FAT/exFAT mounts), so the
     * caller can decide whether that is fatal.
     */
    fun restrictToOwner(path: Path): Boolean {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posix != null) {
            return runCatching {
                posix.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            }.isSuccess
        }

        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return false
        return runCatching {
            val owner = acl.owner
            // setAcl replaces the DACL and marks it protected, so inherited "Users can read"
            // entries from the parent directory are dropped rather than merged.
            acl.setAcl(
                listOf(
                    AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(java.util.EnumSet.allOf(AclEntryPermission::class.java))
                        .build(),
                ),
            )
        }.isSuccess
    }
}

package dk.perspektiva.ttsroad.desktop.ui

import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File

/**
 * The native "choose an EPUB" dialog, behind a seam.
 *
 * A `fun interface` for the same reason `AudiobookSavePicker` is one: a real `FileDialog` needs a
 * display and a person, and every rule worth testing about an upload — the extension, the size
 * ceiling, what happens to the URL field — is about the file that comes *back*.
 */
fun interface EpubFilePicker {
    /** Null means the user cancelled the native dialog, which is not an error. */
    fun choose(): File?
}

object DesktopEpubFilePicker : EpubFilePicker {
    override fun choose(): File? {
        if (GraphicsEnvironment.isHeadless()) return null
        val dialog = FileDialog(null as Frame?, "Choose an EPUB", FileDialog.LOAD)
        // A hint, not a guarantee: several Linux window managers ignore AWT's filter entirely,
        // which is exactly why the extension is checked again once a file comes back.
        dialog.setFilenameFilter { _, name -> name.endsWith(".epub", ignoreCase = true) }
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val filename = dialog.file ?: return null
        return File(directory, filename)
    }
}

/** No picker at all — a test, a preview, or a headless run. */
object UnavailableEpubFilePicker : EpubFilePicker {
    override fun choose(): File? = null
}

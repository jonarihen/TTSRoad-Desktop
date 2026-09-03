package dk.perspektiva.ttsroad.desktop.ui

import dk.perspektiva.ttsroad.desktop.data.CoverImageFormats
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File

/**
 * The native "choose a cover image" dialog, behind a seam.
 *
 * The same shape as [EpubFilePicker] and for the same reason: a real `FileDialog` needs a display
 * and a person, while every rule worth testing about a cover — the format, the size ceiling, what
 * the form does with the file afterwards — is about the file that comes *back*.
 */
fun interface CoverImagePicker {
    /** Null means the user cancelled the native dialog, which is not an error. */
    fun choose(): File?
}

object DesktopCoverImagePicker : CoverImagePicker {
    override fun choose(): File? {
        if (GraphicsEnvironment.isHeadless()) return null
        val dialog = FileDialog(null as Frame?, "Choose cover art", FileDialog.LOAD)
        // A hint, not a guarantee — several Linux window managers ignore AWT's filter outright,
        // which is why the format is checked again once a file comes back.
        dialog.setFilenameFilter { _, name -> CoverImageFormats.isSupported(name) }
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val filename = dialog.file ?: return null
        return File(directory, filename)
    }
}

/** No picker at all — a test, a preview, or a headless run. */
object UnavailableCoverImagePicker : CoverImagePicker {
    override fun choose(): File? = null
}

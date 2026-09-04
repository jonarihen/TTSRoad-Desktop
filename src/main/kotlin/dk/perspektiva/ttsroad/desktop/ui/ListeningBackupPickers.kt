package dk.perspektiva.ttsroad.desktop.ui

import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File

/**
 * Choosing where a listening-state backup goes, and which one to read (#119).
 *
 * The same shape as the pickers beside it and for the same reason: a real `FileDialog` needs a
 * display, so the holder takes the seam and a test supplies a plain file.
 */
fun interface ListeningBackupSavePicker {
    /** Null means the user cancelled. */
    fun choose(suggestedFileName: String): File?
}

fun interface ListeningBackupOpenPicker {
    /** Null means the user cancelled. */
    fun choose(): File?
}

object DesktopListeningBackupSavePicker : ListeningBackupSavePicker {
    override fun choose(suggestedFileName: String): File? {
        if (GraphicsEnvironment.isHeadless()) return null
        val dialog = FileDialog(null as Frame?, "Save listening backup", FileDialog.SAVE)
        dialog.file = suggestedFileName
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val filename = dialog.file ?: return null
        return File(directory, filename)
    }
}

object DesktopListeningBackupOpenPicker : ListeningBackupOpenPicker {
    override fun choose(): File? {
        if (GraphicsEnvironment.isHeadless()) return null
        val dialog = FileDialog(null as Frame?, "Open listening backup", FileDialog.LOAD)
        // A hint several Linux window managers ignore, which is why the holder still checks what
        // came back rather than trusting the filter.
        dialog.setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val filename = dialog.file ?: return null
        return File(directory, filename)
    }
}

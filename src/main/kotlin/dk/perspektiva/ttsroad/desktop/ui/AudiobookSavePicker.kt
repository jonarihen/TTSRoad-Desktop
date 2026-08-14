package dk.perspektiva.ttsroad.desktop.ui

import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File

fun interface AudiobookSavePicker {
    /** Null means the user cancelled the native save dialog. */
    fun choose(suggestedFileName: String): File?
}

object DesktopAudiobookSavePicker : AudiobookSavePicker {
    override fun choose(suggestedFileName: String): File? {
        if (GraphicsEnvironment.isHeadless()) return null
        val dialog = FileDialog(null as Frame?, "Save audiobook", FileDialog.SAVE)
        dialog.file = suggestedFileName
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val filename = dialog.file ?: return null
        return File(directory, filename)
    }
}

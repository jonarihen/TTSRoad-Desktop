package dk.perspektiva.ttsroad.desktop.ui

import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File

fun interface EpubSavePicker {
    fun choose(suggestedFileName: String): File?
}

object DesktopEpubSavePicker : EpubSavePicker {
    override fun choose(suggestedFileName: String): File? {
        if (GraphicsEnvironment.isHeadless()) return null
        val dialog = FileDialog(null as Frame?, "Save EPUB", FileDialog.SAVE)
        return try {
            dialog.file = suggestedFileName
            dialog.isVisible = true
            val directory = dialog.directory ?: return null
            val filename = dialog.file ?: return null
            File(directory, filename)
        } finally {
            dialog.dispose()
        }
    }
}

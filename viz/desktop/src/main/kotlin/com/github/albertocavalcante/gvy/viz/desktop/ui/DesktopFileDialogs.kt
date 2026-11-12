// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.desktop.ui

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileFilter

/** Dialogs are invoked from the desktop UI thread. Null means the user canceled. */
internal interface DesktopFileDialogs {
    fun openFile(initialDirectory: String): File?
    fun saveFile(suggestedFileName: String): File?
}

internal object AwtDesktopFileDialogs : DesktopFileDialogs {
    override fun saveFile(suggestedFileName: String): File? {
        requireAwtThread()
        val chooser = JFileChooser()
        chooser.selectedFile = File(suggestedFileName)
        return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    override fun openFile(initialDirectory: String): File? {
        requireAwtThread()
        return if (System.getProperty("os.name").lowercase().contains("mac")) {
            val dialog = FileDialog(null as Frame?, "Load Groovy File", FileDialog.LOAD)
            try {
                dialog.directory = initialDirectory
                dialog.setFilenameFilter { _, name -> isGroovyFile(name) }
                dialog.isVisible = true
                dialog.file?.let { File(dialog.directory, it) }
            } finally {
                dialog.dispose()
            }
        } else {
            val chooser = JFileChooser(initialDirectory)
            chooser.fileFilter = object : FileFilter() {
                override fun accept(file: File): Boolean = file.isDirectory || isGroovyFile(file.name)
                override fun getDescription(): String = "Groovy Source (*.groovy, *.gradle, Jenkinsfile)"
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }
    }
}

private fun requireAwtThread() {
    check(EventQueue.isDispatchThread()) { "File dialogs must be opened on the AWT event thread" }
}

internal fun isGroovyFile(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".groovy") || lower.endsWith(".gvy") || lower.endsWith(".gradle") || lower == "jenkinsfile"
}

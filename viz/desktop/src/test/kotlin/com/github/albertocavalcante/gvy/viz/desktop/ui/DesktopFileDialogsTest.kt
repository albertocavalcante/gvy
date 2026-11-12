// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.desktop.ui

import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopFileDialogsTest {
    @Test
    fun `Groovy filename filter retains supported extensions and Jenkinsfile`() {
        for (name in listOf(
            "build.groovy",
            "script.gvy",
            "settings.gradle",
            "Jenkinsfile",
            "SCRIPT.GROOVY",
            "JENKINSFILE",
        )) {
            assertTrue(isGroovyFile(name), name)
        }
        for (name in listOf("", "script.txt", "script.groovy.bak", "myJenkinsfile", "gradle")) {
            assertFalse(isGroovyFile(name), name)
        }
    }

    @Test
    fun `production dialogs reject calls outside the AWT event thread`() {
        assertTrue(GraphicsEnvironment.isHeadless(), "This test must not open a real dialog")
        val openFailure = AtomicReference<Throwable?>()
        val saveFailure = AtomicReference<Throwable?>()
        val thread = Thread {
            openFailure.set(runCatching { AwtDesktopFileDialogs.openFile(".") }.exceptionOrNull())
            saveFailure.set(runCatching { AwtDesktopFileDialogs.saveFile("ast.json") }.exceptionOrNull())
        }
        thread.isDaemon = true
        thread.start()
        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertIs<IllegalStateException>(openFailure.get())
        assertIs<IllegalStateException>(saveFailure.get())
        assertEquals("File dialogs must be opened on the AWT event thread", openFailure.get()?.message)
        assertEquals("File dialogs must be opened on the AWT event thread", saveFailure.get()?.message)
    }
}

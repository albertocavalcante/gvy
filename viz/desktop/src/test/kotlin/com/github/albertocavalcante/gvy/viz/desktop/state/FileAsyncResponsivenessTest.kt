package com.github.albertocavalcante.gvy.viz.desktop.state

import java.awt.EventQueue
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

class FileAsyncResponsivenessTest {
    @Test
    fun `file load returns to the event queue while the operation is pending`() {
        val file = Files.createTempFile("gvy-load-test-", ".groovy")
        try {
            Files.writeString(file, "def value = 1")
            val sourceBeforeReturn = AtomicReference<String>()
            EventQueue.invokeAndWait {
                val model = AstViewModel(InMemoryPreferences())
                model.loadFile(file.toFile())
                sourceBeforeReturn.set(model.sourceCode)
                model.close()
            }
            assertEquals("", sourceBeforeReturn.get(), "File contents must be delivered after the UI callback returns")
        } finally {
            Files.deleteIfExists(file)
        }
    }
}

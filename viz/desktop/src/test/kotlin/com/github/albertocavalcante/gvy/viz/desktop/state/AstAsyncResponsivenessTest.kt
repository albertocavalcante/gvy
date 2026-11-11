package com.github.albertocavalcante.gvy.viz.desktop.state

import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

class AstAsyncResponsivenessTest {
    @Test
    fun `parse request returns to the event queue before parsing completes`() {
        val parsingAfterRequest = AtomicBoolean(false)
        EventQueue.invokeAndWait {
            val model = AstViewModel(InMemoryPreferences())
            model.updateSourceCode("def value = 1")
            model.parseCode()
            parsingAfterRequest.set(model.isParsing)
            model.close()
        }
        assertTrue(parsingAfterRequest.get(), "A submitted parse must remain pending until the UI callback returns")
    }
}

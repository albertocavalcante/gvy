package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.gls.services.Formatter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class FormattingActionFailureTest {
    @Test
    fun `formatting cancellation reaches the caller`() {
        val failure = CancellationException("formatting cancelled")
        val action = FormattingAction(Formatter { throw failure })

        assertThatThrownBy { action.createFormattingAction(URI, SOURCE) }.isSameAs(failure)
    }

    @Test
    fun `fatal formatting failure reaches the caller`() {
        val failure = LinkageError("formatter unavailable")
        val action = FormattingAction(Formatter { throw failure })

        assertThatThrownBy { action.createFormattingAction(URI, SOURCE) }.isSameAs(failure)
    }

    @Test
    fun `ordinary engine failure does not offer a formatting action`() {
        val action = FormattingAction(Formatter { throw IOException("formatter unavailable") })

        assertThat(action.createFormattingAction(URI, SOURCE)).isNull()
    }

    private companion object {
        const val URI = "file:///formatting.groovy"
        const val SOURCE = "def x=1\n"
    }
}

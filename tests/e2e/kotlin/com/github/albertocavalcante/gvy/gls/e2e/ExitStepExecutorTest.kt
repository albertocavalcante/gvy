package com.github.albertocavalcante.gvy.gls.e2e

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExitStepExecutorTest {
    private val session = mockk<LanguageServerSession>(relaxed = true)
    private val context = mockk<ScenarioContext>().also { context ->
        every { context.session } returns session
    }

    @Test
    fun `the exit step delegates to observed exit validation instead of only sending a notification`() {
        ExitStepExecutor().execute(ScenarioStep.Exit, context)

        verify(exactly = 1) { session.exit() }
        verify(exactly = 0) { session.server.exit() }
    }

    @Test
    fun `an observed exit failure fails the scenario`() {
        val failure = AssertionError("exit status differed")
        every { session.exit() } throws failure

        assertThatThrownBy { ExitStepExecutor().execute(ScenarioStep.Exit, context) }.isSameAs(failure)
    }
}

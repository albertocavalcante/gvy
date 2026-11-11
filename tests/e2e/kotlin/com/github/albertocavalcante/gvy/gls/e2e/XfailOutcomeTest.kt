package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import org.opentest4j.TestAbortedException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

class XfailOutcomeTest {
    private val xfail = Xfail(issue = 42, reason = "known bug")
    private val file = "demo.yaml"

    private fun classify(x: Xfail = xfail, failure: Throwable?) = XfailOutcome.classify(x, file, failure)

    @Test
    fun `assertion failure is expected`() {
        assertThat(classify(failure = AssertionFailedError("boom"))).isInstanceOf(XfailDecision.Expected::class.java)
    }

    @Test
    fun `timeout is expected only with a match`() {
        val timeout = TimeoutException("Timeout waiting for notification 'x'")
        assertThat(classify(failure = timeout)).isEqualTo(XfailDecision.Unexpected(timeout))
        assertThat(classify(xfail.copy(match = "notification 'x'"), timeout))
            .isInstanceOf(XfailDecision.Expected::class.java)
        assertThat(classify(xfail.copy(match = "other"), timeout)).isEqualTo(XfailDecision.Unexpected(timeout))
    }

    @Test
    fun `readiness waits are exempt from the cap`() {
        assertThat(XfailOutcome.isReadinessWait("groovy/status")).isTrue()
        assertThat(XfailOutcome.isReadinessWait("textDocument/publishDiagnostics")).isFalse()
    }

    @Test
    fun `execution exception wrapping a response error is expected`() {
        val error = ResponseErrorException(ResponseError(ResponseErrorCode.InternalError, "server broke", null))
        assertThat(classify(failure = ExecutionException(error))).isInstanceOf(XfailDecision.Expected::class.java)
    }

    @Test
    fun `execution exception wrapping something else is unexpected`() {
        val failure = ExecutionException(IllegalStateException("harness"))
        assertThat(classify(failure = failure)).isEqualTo(XfailDecision.Unexpected(failure))
    }

    @Test
    fun `other errors are unexpected`() {
        val failure = IllegalArgumentException("bad yaml")
        assertThat(classify(failure = failure)).isEqualTo(XfailDecision.Unexpected(failure))
    }

    @Test
    fun `match must be found in the failure message`() {
        val x = xfail.copy(match = "expected: 1")
        assertThat(
            classify(x, AssertionFailedError("expected: 1 but was: 2")),
        ).isInstanceOf(XfailDecision.Expected::class.java)
        val other = AssertionFailedError("something else failed")
        assertThat(classify(x, other)).isEqualTo(XfailDecision.Unexpected(other))
    }

    @Test
    fun `match also searches the causes`() {
        val x = xfail.copy(match = "server broke")
        val error = ResponseErrorException(ResponseError(ResponseErrorCode.InternalError, "server broke", null))
        assertThat(classify(x, ExecutionException(error))).isInstanceOf(XfailDecision.Expected::class.java)
    }

    @Test
    fun `passing scenario is reported as fixed`() {
        assertThat(classify(failure = null))
            .isEqualTo(XfailDecision.Fixed("gvy#42 appears fixed: remove xfail from demo.yaml"))
    }

    @Test
    fun `run aborts, rethrows or fails according to the decision`() {
        assertThatThrownBy { XfailOutcome.run(xfail, file) { throw AssertionFailedError("boom") } }
            .isInstanceOf(TestAbortedException::class.java)
            .hasMessageContaining("xfail gvy#42: known bug")
        assertThatThrownBy { XfailOutcome.run(xfail, file) { throw IllegalStateException("harness") } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { XfailOutcome.run(xfail, file) {} }
            .isInstanceOf(AssertionError::class.java)
            .hasMessage("gvy#42 appears fixed: remove xfail from demo.yaml")
    }

    @Test
    fun `display name carries the issue`() {
        val scenario = Scenario(name = "demo", xfail = xfail, steps = emptyList())
        assertThat(XfailOutcome.displayName(scenario)).isEqualTo("[xfail gvy#42] demo")
        assertThat(XfailOutcome.displayName(scenario.copy(xfail = null))).isEqualTo("demo")
    }

    @Test
    fun `timeout cap lowers only longer timeouts`() {
        val cap = XfailOutcome.STEP_TIMEOUT_CAP_MS
        assertThat(XfailOutcome.capTimeout(60_000, cap)).isEqualTo(cap)
        assertThat(XfailOutcome.capTimeout(1_000, cap)).isEqualTo(1_000)
        assertThat(XfailOutcome.capTimeout(60_000, null)).isEqualTo(60_000)
    }

    @Test
    fun `parser reads xfail and rejects unknown keys and bad regex`() {
        val parser = JacksonScenarioParser()
        val ok = parser.parseScenarioDefinition(
            "name: a\nxfail: { issue: 7, reason: r, match: 'foo.*' }\nsteps: []\n",
        )
        assertThat(ok.scenario.xfail).isEqualTo(Xfail(7, "r", "foo.*"))
        assertThatThrownBy {
            parser.parseScenarioDefinition("name: a\nxfail: { issue: 7, reason: r, why: x }\nsteps: []\n")
        }
            .isInstanceOf(ScenarioParseException::class.java)
        assertThatThrownBy {
            parser.parseScenarioDefinition("name: a\nxfail: { issue: 7, reason: r, match: '(' }\nsteps: []\n")
        }
            .isInstanceOf(ScenarioParseException::class.java)
        assertThatThrownBy { parser.parseScenarioDefinition("name: a\nxfail: { reason: r }\nsteps: []\n") }
            .isInstanceOf(ScenarioParseException::class.java)
    }
}

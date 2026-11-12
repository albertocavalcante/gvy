package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import org.opentest4j.TestAbortedException
import java.io.IOException
import java.util.concurrent.CancellationException
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
    fun `matching a cyclic cause chain terminates`() {
        var causeReads = 0
        val failure = object : AssertionError("product failed") {
            override val cause: Throwable?
                get() {
                    check(++causeReads <= 3) { "cause chain was not bounded" }
                    return this
                }
        }

        assertThat(classify(xfail.copy(match = "product failed"), failure))
            .isInstanceOf(XfailDecision.Expected::class.java)
    }

    @Test
    fun `oversized matched or unmatched failures are not known failures`() {
        val failure = AssertionError("known " + "x".repeat(100_000))
        listOf(xfail, xfail.copy(match = "known")).forEach { declaration ->
            assertThat(classify(declaration, failure)).isInstanceOf(XfailDecision.Unexpected::class.java)
        }
    }

    @Test
    fun `uninspected deep cause is not classified as known`() {
        val failure = AssertionError("known")
        var tail: Throwable = failure
        repeat(100) { index -> IOException("cause-$index").also { tail.initCause(it); tail = it } }
        assertThat(classify(xfail.copy(match = "known"), failure))
            .isInstanceOf(XfailDecision.Unexpected::class.java)
    }

    @Test
    fun `unsupported lookbehind fails before classification`() {
        assertThatThrownBy { classify(xfail.copy(match = "(?<=a)b"), AssertionError("ab")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `incomplete inspection preserves the original failure as cause`() {
        val failure = AssertionError("known " + "x".repeat(100_000))
        val decision = classify(xfail.copy(match = "known"), failure) as XfailDecision.Unexpected
        assertThat(decision.failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(decision.failure.message).contains("classification incomplete")
        assertThat(decision.failure.cause).isSameAs(failure)
    }

    @Test
    fun `hostile failure accessors cannot produce a known failure`() {
        val failure = object : AssertionError() {
            override val message: String get() = throw IllegalStateException("cannot inspect")
        }
        val decision = classify(xfail.copy(match = "anything"), failure) as XfailDecision.Unexpected
        assertThat(decision.failure.message).contains("unavailable")
        assertThat(decision.failure.cause).isSameAs(failure)
    }

    @Test
    fun `classifier preserves fatal and cancellation thrown during inspection`() {
        listOf(CancellationException("cancel"), LinkageError("fatal")).forEach { thrown ->
            val failure = object : AssertionError() {
                override val message: String get() = throw thrown
            }
            assertThatThrownBy { classify(xfail.copy(match = "anything"), failure) }.isSameAs(thrown)
        }
    }

    @Test
    fun `ambiguous repetition uses bounded linear matcher`() {
        val failure = AssertionError("a".repeat(8_000) + "b")
        assertThat(classify(xfail.copy(match = "(a+)+$"), failure))
            .isEqualTo(XfailDecision.Unexpected(failure))
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
    fun `run aborts for matching response errors and timeouts`() {
        val response = ResponseErrorException(ResponseError(ResponseErrorCode.InternalError, "product failed", null))
        val failures = listOf(ExecutionException(response), TimeoutException("product failed"))

        failures.forEach { failure ->
            assertThatThrownBy { XfailOutcome.run(xfail.copy(match = "product failed"), file) { throw failure } }
                .isInstanceOf(TestAbortedException::class.java)
                .hasMessageContaining("xfail gvy#42: known bug")
        }
    }

    @Test
    fun `run preserves harness cancellation interruption and fatal failures`() {
        val failures = listOf(
            IllegalStateException("harness failed"),
            IllegalArgumentException("bad scenario"),
            ExecutionException(IllegalStateException("harness failed")),
            CancellationException("cancelled"),
            InterruptedException("interrupted"),
            LinkageError("broken runtime"),
        )

        failures.forEach { failure ->
            assertThatThrownBy { XfailOutcome.run(xfail, file) { throw failure } }
                .isSameAs(failure)
        }
    }

    @Test
    fun `run preserves timeouts without a matching declaration`() {
        val failure = TimeoutException("unrelated timeout")

        listOf(xfail, xfail.copy(match = "product failed")).forEach { declaration ->
            assertThatThrownBy { XfailOutcome.run(declaration, file) { throw failure } }
                .isSameAs(failure)
        }
    }

    @Test
    fun `matching assertion with failed cleanup remains a failure`() {
        val assertion = AssertionFailedError("product failed")
        val cleanup = IOException("cleanup failed")

        assertThatThrownBy {
            XfailOutcome.run(xfail.copy(match = "product failed"), file) {
                AutoCloseable { throw cleanup }.use { throw assertion }
            }
        }.isSameAs(assertion)
        assertThat(assertion.suppressed).containsExactly(cleanup)
    }

    @Test
    fun `matching response error with suppressed failure on its cause remains a failure`() {
        val response = ResponseErrorException(ResponseError(ResponseErrorCode.InternalError, "product failed", null))
        val cleanup = IOException("cleanup failed")
        response.addSuppressed(cleanup)
        val failure = ExecutionException(response)

        assertThatThrownBy {
            XfailOutcome.run(xfail.copy(match = "product failed"), file) { throw failure }
        }.isSameAs(failure)
        assertThat(response.suppressed).containsExactly(cleanup)
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
        val shorter = cap / 2
        val longer = cap * 2
        assertThat(XfailOutcome.capTimeout(longer, cap)).isEqualTo(cap)
        assertThat(XfailOutcome.capTimeout(shorter, cap)).isEqualTo(shorter)
        assertThat(XfailOutcome.capTimeout(longer, null)).isEqualTo(longer)
    }

    @Test
    fun `parser reads xfail and rejects unknown keys and bad regex`() {
        val parser = JacksonScenarioParser()
        val ok = parser.parseScenarioDefinition(
            "name: a\nxfail: { issue: 7, reason: r, match: 'foo.*' }\nsteps: []\n",
        )
        assertThat(ok.scenario.xfail).isEqualTo(Xfail(issue = 7, reason = "r", match = "foo.*"))
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

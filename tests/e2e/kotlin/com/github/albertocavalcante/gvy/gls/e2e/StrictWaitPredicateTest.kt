// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.testing.client.HarnessLanguageClient
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

class StrictWaitPredicateTest {
    @Test
    fun `optional wait propagates evaluator failure`() = assertPropagated(IllegalStateException("evaluator failed"))

    @Test
    fun `optional wait propagates cancellation`() = assertPropagated(CancellationException("cancelled"))

    @Test
    fun `optional wait propagates fatal error`() = assertPropagated(LinkageError("fatal"))

    @Test
    fun `optional wait reports evaluator timeout as a harness failure`() =
        assertXfailDoesNotHide(TimeoutException("product failed"))

    @Test
    fun `wrapped cancellation propagates through a wait`() =
        assertWrappedCausePropagated(CancellationException("cancelled"))

    @Test
    fun `wrapped fatal error propagates through a wait`() = assertWrappedCausePropagated(LinkageError("fatal"))

    @Test
    fun `nested wrapped cancellation propagates through a wait`() {
        val cancelled = CancellationException("cancelled")
        val cleanup = java.io.IOException("cleanup failed")
        val inner = ExecutionException(cancelled).apply { addSuppressed(cleanup) }
        assertWrappedCausePropagated(cancelled, CompletionException(inner))
        assertThat(cancelled.suppressed).containsExactly(cleanup)
    }

    @Test
    fun `wrapped interruption restores interrupt and propagates`() {
        try {
            assertWrappedCausePropagated(InterruptedException("interrupted"))
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `direct and wrapped IO interruptions retain identity and interrupt flag`() {
        val failures =
            listOf(java.io.InterruptedIOException("interrupted"), java.nio.channels.ClosedByInterruptException())
        for (failure in failures) {
            try {
                assertPropagated(failure)
                assertThat(Thread.currentThread().isInterrupted).isTrue()
                Thread.interrupted()
                assertWrappedCausePropagated(failure)
                assertThat(Thread.currentThread().isInterrupted).isTrue()
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun `socket timeout remains an ordinary harness failure`() {
        val timeout = java.net.SocketTimeoutException("socket timeout")
        try {
            assertPropagated(timeout)
            assertThat(Thread.currentThread().isInterrupted).isFalse()
            assertWrappedCausePropagatedAsHarness(CompletionException(timeout))
            assertThat(Thread.currentThread().isInterrupted).isFalse()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `deep wrapper graph remains a bounded harness failure retaining its cause`() {
        var failure: Throwable = CancellationException("deep")
        repeat(33) { failure = CompletionException(failure) }
        assertWrappedCausePropagatedAsHarness(failure)
    }

    @Test
    fun `maximum wrapper depth retains cancellation identity`() {
        val cancellation = CancellationException("bounded")
        var wrapper: Throwable = cancellation
        repeat(32) { wrapper = CompletionException(wrapper) }
        assertWrappedCausePropagated(cancellation, wrapper)
    }

    @Test
    fun `maximum retained suppressed evidence preserves cancellation`() {
        val cancellation = CancellationException("cancelled")
        val wrapper = CompletionException(cancellation)
        val failures = List(31) { IllegalStateException("cleanup") }
        failures.forEach(wrapper::addSuppressed)
        assertWrappedCausePropagated(cancellation, wrapper)
        assertThat(cancellation.suppressed.toList()).containsExactlyElementsOf(failures)
    }

    @Test
    fun `wrapper suppression does not create a cycle`() {
        val cancellation = CancellationException("cancelled")
        val inner = CompletionException(cancellation)
        val outer = CompletionException(inner)
        outer.addSuppressed(inner)
        assertWrappedCausePropagatedAsHarness(outer)
        assertThat(cancellation.suppressed).isEmpty()
    }

    @Test
    fun `excess suppressed evidence retains original wrapper as harness failure`() {
        val wrapper = CompletionException(CancellationException("cancelled"))
        repeat(33) { wrapper.addSuppressed(IllegalStateException("cleanup")) }
        assertWrappedCausePropagatedAsHarness(wrapper)
    }

    @Test
    fun `interrupt thrown by wrapper cause accessor retains identity and flag`() {
        val interrupted = java.io.InterruptedIOException("getter interrupted")
        val wrapper = object : CompletionException(null as Throwable?) {
            override val cause: Throwable? get() = throw interrupted
        }
        try {
            assertWrappedCausePropagated(interrupted, wrapper)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `critical cause getter failure retains wrapper cleanup`() {
        val controls = listOf(
            java.io.InterruptedIOException("interrupted"),
            java.nio.channels.ClosedByInterruptException(),
            CancellationException("cancelled"),
            LinkageError("fatal"),
        )
        for (control in controls) {
            val cleanup = java.io.IOException("cleanup")
            val wrapper = object : CompletionException(null as Throwable?) {
                override val cause: Throwable? get() = throw control
            }.apply { addSuppressed(cleanup) }
            try {
                assertWrappedCausePropagated(control, wrapper)
                assertThat(control.suppressed).containsExactly(cleanup)
                if (control is java.io.InterruptedIOException ||
                    control is java.nio.channels.ClosedByInterruptException
                ) {
                    assertThat(Thread.currentThread().isInterrupted).isTrue()
                }
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun `overflowing getter cleanup retains original graph and getter interruption`() {
        val interrupted = java.io.InterruptedIOException("getter interrupted")
        val wrapper = object : CompletionException(null as Throwable?) {
            override val cause: Throwable? get() = throw interrupted
        }.apply { repeat(33) { addSuppressed(java.io.IOException("cleanup")) } }
        val client = HarnessLanguageClient()
        client.telemetryEvent("candidate")
        val session = mockk<LanguageServerSession>()
        val context = mockk<ScenarioContext>()
        every { session.client } returns client
        every { context.session } returns session
        every { context.interpolateString("$") } returns "$"
        every { context.effectiveTimeoutMs(0) } returns 0
        every { context.evaluateCheck(any(), any(), true) } throws wrapper
        val step = ScenarioStep.WaitNotification(
            method = "telemetry/event",
            checks = listOf(JsonCheck("$", JsonExpectation())),
            optional = true,
            timeoutMs = 0,
        )
        try {
            val failure = runCatching { WaitNotificationStepExecutor().execute(step, context) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java).hasCause(wrapper)
            assertThat(failure?.suppressed).containsExactly(interrupted)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `xfail cannot hide assertion thrown by cause getter`() {
        for (withCleanup in listOf(false, true)) {
            val getterFailure = AssertionError("product failed")
            val cleanup = java.io.IOException("cleanup")
            val wrapper = object : CompletionException(null as Throwable?) {
                override val cause: Throwable? get() = throw getterFailure
            }.apply { if (withCleanup) addSuppressed(cleanup) }
            val client = HarnessLanguageClient()
            client.telemetryEvent("candidate")
            val session = mockk<LanguageServerSession>()
            val context = mockk<ScenarioContext>()
            every { session.client } returns client
            every { context.session } returns session
            every { context.interpolateString("$") } returns "$"
            every { context.effectiveTimeoutMs(0) } returns 0
            every { context.evaluateCheck(any(), any(), true) } throws wrapper
            val step = ScenarioStep.WaitNotification(
                method = "telemetry/event",
                checks = listOf(JsonCheck("$", JsonExpectation())),
                optional = true,
                timeoutMs = 0,
            )
            val failure = runCatching {
                XfailOutcome.run(Xfail(issue = 42, reason = "known", match = "product failed"), "test.yaml") {
                    WaitNotificationStepExecutor().execute(step, context)
                }
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java).hasCause(wrapper)
            assertThat(failure?.suppressed).containsExactly(getterFailure)
            if (withCleanup) {
                assertThat(wrapper.suppressed).containsExactly(cleanup)
            } else {
                assertThat(wrapper.suppressed).isEmpty()
            }
        }
    }

    private fun assertWrappedCausePropagatedAsHarness(failure: Throwable) {
        val client = HarnessLanguageClient()
        client.telemetryEvent("candidate")
        val session = mockk<LanguageServerSession>()
        val context = mockk<ScenarioContext>()
        every { session.client } returns client
        every { context.session } returns session
        every { context.interpolateString("$") } returns "$"
        every { context.effectiveTimeoutMs(0) } returns 0
        every { context.evaluateCheck(any(), any(), true) } throws failure
        val step = ScenarioStep.WaitNotification(
            method = "telemetry/event",
            checks = listOf(JsonCheck("$", JsonExpectation())),
            optional = true,
            timeoutMs = 0,
        )
        assertThatThrownBy { WaitNotificationStepExecutor().execute(step, context) }
            .isInstanceOf(IllegalStateException::class.java).hasCause(failure)
    }

    private fun assertWrappedCausePropagated(cause: Throwable, wrapper: Throwable = ExecutionException(cause)) {
        val client = HarnessLanguageClient()
        client.telemetryEvent("candidate")
        val session = mockk<LanguageServerSession>()
        val context = mockk<ScenarioContext>()
        every { session.client } returns client
        every { context.session } returns session
        every { context.interpolateString("$") } returns "$"
        every { context.effectiveTimeoutMs(0) } returns 0
        every { context.evaluateCheck(any(), any(), true) } throws wrapper
        val step = ScenarioStep.WaitNotification(
            method = "telemetry/event",
            checks = listOf(JsonCheck("$", JsonExpectation())),
            optional = true,
            timeoutMs = 0,
        )

        assertThatThrownBy { WaitNotificationStepExecutor().execute(step, context) }.isSameAs(cause)
        assertThat(client.peekNotification("telemetry/event") { true }).isTrue()
    }

    @Test
    fun `optional lookahead propagates evaluator failure without consuming notification`() {
        val failure = IllegalStateException("lookahead failed")
        val client = HarnessLanguageClient()
        client.telemetryEvent("candidate")
        val session = mockk<LanguageServerSession>()
        val context = mockk<ScenarioContext>()
        every { session.client } returns client
        every { context.session } returns session
        every { context.interpolateString("$") } returns "$"
        every { context.evaluateCheck(any(), any(), true) } throws failure
        val current = ScenarioStep.WaitNotification(method = "other", optional = true, timeoutMs = 0)
        val next = ScenarioStep.WaitNotification(
            method = "telemetry/event",
            checks = listOf(JsonCheck("$", JsonExpectation())),
        )

        assertThatThrownBy { WaitNotificationStepExecutor().execute(current, context, next) }.isSameAs(failure)
        assertThat(client.peekNotification("telemetry/event") { true }).isTrue()
    }

    @Test
    fun `lookahead uses interpolation that is already available`() {
        val client = HarnessLanguageClient()
        client.telemetryEvent("candidate")
        val session = mockk<LanguageServerSession>()
        val context = mockk<ScenarioContext>()
        every { session.client } returns client
        every { context.session } returns session
        every { context.interpolateString("{{known}}") } returns "$"
        every { context.evaluateCheck(any(), any(), true) } returns true
        every { context.effectiveTimeoutMs(0) } returns 0
        val current = ScenarioStep.WaitNotification(method = "other", optional = true, timeoutMs = 0)
        val next = ScenarioStep.WaitNotification(
            method = "telemetry/event",
            checks = listOf(JsonCheck("{{known}}", JsonExpectation())),
        )

        WaitNotificationStepExecutor().execute(current, context, next)

        io.mockk.verify(exactly = 1) { context.evaluateCheck(any(), any(), true) }
    }

    @Test
    fun `xfail cannot hide matching evaluator assertion`() = assertXfailDoesNotHide(AssertionError("product failed"))

    @Test
    fun `xfail cannot hide matching evaluator timeout`() = assertXfailDoesNotHide(TimeoutException("product failed"))

    @Test
    fun `xfail cannot hide payload conversion failure`() {
        val failure = AssertionError("product failed")
        val key = object {
            override fun toString(): String = throw failure
        }
        val client = HarnessLanguageClient()
        client.telemetryEvent(mapOf(key to "value"))
        val session = mockk<LanguageServerSession>()
        val context = mockk<ScenarioContext>()
        every { session.client } returns client
        every { context.session } returns session
        every { context.interpolateString("$") } returns "$"
        every { context.effectiveTimeoutMs(0) } returns 0
        val step = ScenarioStep.WaitNotification(
            method = "telemetry/event",
            checks = listOf(JsonCheck("$", JsonExpectation())),
            optional = true,
            timeoutMs = 0,
        )

        assertThatThrownBy {
            XfailOutcome.run(Xfail(issue = 42, reason = "known", match = "product failed"), "test.yaml") {
                WaitNotificationStepExecutor().execute(step, context)
            }
        }.isInstanceOf(IllegalStateException::class.java).hasCause(failure)
        assertThat(client.peekNotification("telemetry/event") { true }).isTrue()
    }

    @Test
    fun `xfail cannot hide matching lookahead assertion`() {
        val failure = AssertionError("product failed")
        val client = HarnessLanguageClient()
        client.telemetryEvent("candidate")
        val session = mockk<LanguageServerSession>()
        val context = mockk<ScenarioContext>()
        every { session.client } returns client
        every { context.session } returns session
        every { context.interpolateString("$") } returns "$"
        every { context.evaluateCheck(any(), any(), true) } throws failure
        val current = ScenarioStep.WaitNotification(method = "other", optional = true, timeoutMs = 0)
        val next = ScenarioStep.WaitNotification(
            method = "telemetry/event",
            checks = listOf(JsonCheck("$", JsonExpectation())),
        )

        assertThatThrownBy {
            XfailOutcome.run(Xfail(issue = 42, reason = "known", match = "product failed"), "test.yaml") {
                WaitNotificationStepExecutor().execute(current, context, next)
            }
        }.isInstanceOf(IllegalStateException::class.java).hasCause(failure)
        assertThat(client.peekNotification("telemetry/event") { true }).isTrue()
    }

    @Test
    fun `xfail cannot hide a final evaluator timeout`() {
        val failure = TimeoutException("product failed")
        val client = HarnessLanguageClient()
        client.telemetryEvent("candidate")
        val session = mockk<LanguageServerSession>()
        val context = mockk<ScenarioContext>(relaxed = true)
        every { session.client } returns client
        every { context.session } returns session
        every { context.interpolateString("$") } returns "$"
        every { context.effectiveTimeoutMs(0) } returns 0
        every { context.evaluateCheck(any(), any(), true) } returns true
        every { context.evaluateCheck(any(), any(), false) } throws failure
        val step = ScenarioStep.WaitNotification(
            method = "telemetry/event",
            checks = listOf(JsonCheck("$", JsonExpectation())),
            timeoutMs = 0,
        )

        assertThatThrownBy {
            XfailOutcome.run(Xfail(issue = 42, reason = "known", match = "product failed"), "test.yaml") {
                WaitNotificationStepExecutor().execute(step, context)
            }
        }.isInstanceOf(IllegalStateException::class.java).hasCause(failure)
    }

    @Test
    fun `final evaluator preserves interruption identity and flag`() {
        val failures = listOf(
            InterruptedException("interrupted"),
            java.io.InterruptedIOException("interrupted"),
            java.nio.channels.ClosedByInterruptException(),
            java.net.SocketTimeoutException("timeout"),
        )
        for (failure in failures) {
            val client = HarnessLanguageClient()
            client.telemetryEvent("candidate")
            val session = mockk<LanguageServerSession>()
            val context = mockk<ScenarioContext>(relaxed = true)
            every { session.client } returns client
            every { context.session } returns session
            every { context.interpolateString("$") } returns "$"
            every { context.effectiveTimeoutMs(0) } returns 0
            every { context.evaluateCheck(any(), any(), true) } returns true
            every { context.evaluateCheck(any(), any(), false) } throws failure
            val step = ScenarioStep.WaitNotification(
                method = "telemetry/event",
                checks = listOf(JsonCheck("$", JsonExpectation())),
                timeoutMs = 0,
            )
            try {
                assertThatThrownBy { WaitNotificationStepExecutor().execute(step, context) }.isSameAs(failure)
                assertThat(Thread.currentThread().isInterrupted).isEqualTo(failure !is java.net.SocketTimeoutException)
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun `final assertion mismatch remains eligible for xfail`() {
        val client = HarnessLanguageClient()
        client.telemetryEvent("candidate")
        val session = mockk<LanguageServerSession>()
        val context = mockk<ScenarioContext>(relaxed = true)
        every { session.client } returns client
        every { context.session } returns session
        every { context.interpolateString("$") } returns "$"
        every { context.effectiveTimeoutMs(0) } returns 0
        every { context.evaluateCheck(any(), any(), true) } returns true
        every { context.evaluateCheck(any(), any(), false) } throws AssertionError("product failed")
        val step = ScenarioStep.WaitNotification(
            method = "telemetry/event",
            checks = listOf(JsonCheck("$", JsonExpectation())),
            timeoutMs = 0,
        )

        assertThatThrownBy {
            XfailOutcome.run(Xfail(issue = 42, reason = "known", match = "product failed"), "test.yaml") {
                WaitNotificationStepExecutor().execute(step, context)
            }
        }.isInstanceOf(org.opentest4j.TestAbortedException::class.java)
    }

    private fun assertXfailDoesNotHide(failure: Throwable) {
        val client = HarnessLanguageClient()
        client.telemetryEvent("candidate")
        val session = mockk<LanguageServerSession>()
        val context = mockk<ScenarioContext>()
        every { session.client } returns client
        every { context.session } returns session
        every { context.interpolateString("$") } returns "$"
        every { context.effectiveTimeoutMs(0) } returns 0
        every { context.evaluateCheck(any(), any(), true) } throws failure
        val step = ScenarioStep.WaitNotification(
            method = "telemetry/event",
            checks = listOf(JsonCheck("$", JsonExpectation())),
            optional = true,
            timeoutMs = 0,
        )

        assertThatThrownBy {
            XfailOutcome.run(Xfail(issue = 42, reason = "known", match = "product failed"), "test.yaml") {
                WaitNotificationStepExecutor().execute(step, context)
            }
        }.isInstanceOf(IllegalStateException::class.java).hasCause(failure)
        assertThat(client.peekNotification("telemetry/event") { true }).isTrue()
    }

    @Test
    fun `lookahead defers checks that require future variables`() {
        val client = HarnessLanguageClient()
        client.telemetryEvent("candidate")
        val session = mockk<LanguageServerSession>()
        val context = mockk<ScenarioContext>()
        every { session.client } returns client
        every { context.session } returns session
        every { context.effectiveTimeoutMs(0) } returns 0
        val current = ScenarioStep.WaitNotification(method = "other", optional = true, saveAs = "future", timeoutMs = 0)
        val next = ScenarioStep.WaitNotification(
            method = "telemetry/event",
            checks = listOf(JsonCheck("{{future}}", JsonExpectation())),
        )

        WaitNotificationStepExecutor().execute(current, context, next)

        assertThat(client.peekNotification("telemetry/event") { true }).isTrue()
    }

    private fun assertPropagated(failure: Throwable) {
        val client = HarnessLanguageClient()
        client.telemetryEvent("candidate")
        val session = mockk<LanguageServerSession>()
        val context = mockk<ScenarioContext>()
        every { session.client } returns client
        every { context.session } returns session
        every { context.interpolateString("$") } returns "$"
        every { context.effectiveTimeoutMs(0) } returns 0
        every { context.evaluateCheck(any(), any(), true) } throws failure
        val step = ScenarioStep.WaitNotification(
            method = "telemetry/event",
            checks = listOf(JsonCheck("$", JsonExpectation())),
            optional = true,
            timeoutMs = 0,
        )

        assertThatThrownBy { WaitNotificationStepExecutor().execute(step, context) }.isSameAs(failure)
        assertThat(client.peekNotification("telemetry/event") { true }).isTrue()
    }
}

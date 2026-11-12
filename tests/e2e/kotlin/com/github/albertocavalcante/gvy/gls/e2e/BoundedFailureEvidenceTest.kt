package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class BoundedFailureEvidenceTest {
    @Test
    fun `nested causes and suppressed retain their origins`() {
        val failure = IllegalStateException("outer", IOException("inner"))
        failure.addSuppressed(IllegalArgumentException("cleanup"))
        failure.stackTrace = emptyArray()
        failure.cause!!.stackTrace = emptyArray()
        failure.suppressed.single().stackTrace = emptyArray()

        val evidence = BoundedFailureEvidence.capture(failure)

        assertThat(
            evidence.text,
        ).contains(
            "outer",
            "Caused by: java.io.IOException: inner",
            "Suppressed: java.lang.IllegalArgumentException: cleanup",
        )
        assertThat(evidence.truncated).isFalse()
        assertThat(evidence.unavailable).isFalse()
    }

    @Test
    fun `bounded unicode text marks truncation without splitting a code point`() {
        val evidence = BoundedFailureEvidence.capture(IOException("😀".repeat(20_000)))

        assertThat(evidence.truncated).isTrue()
        assertThat(evidence.text).endsWith("[truncated]")
        assertThat(
            evidence.text.toByteArray(Charsets.UTF_8).size,
        ).isLessThanOrEqualTo(BoundedFailureEvidence.MAX_UTF8_BYTES)
        assertThat(evidence.text).doesNotContain("�")
    }

    @Test
    fun `cause cycles and shared suppressed nodes terminate explicitly`() {
        val first = IOException("first")
        val second = IOException("second")
        first.initCause(second)
        second.initCause(first)
        first.addSuppressed(second)

        val evidence = BoundedFailureEvidence.capture(first)

        assertThat(evidence.text).contains("first", "second", "[truncated]")
        assertThat(evidence.truncated).isTrue()
    }

    @Test
    fun `wide deep and many frame inputs report bounded evidence`() {
        val root = IOException("root")
        root.stackTrace = Array(1_000) { StackTraceElement("Owner", "m$it", "File.groovy", it) }
        repeat(100) { root.addSuppressed(IOException("suppressed-$it").apply { stackTrace = emptyArray() }) }
        var tail = root
        repeat(100) { index ->
            IOException("cause-$index").also {
                it.stackTrace = emptyArray()
                tail.initCause(it)
                tail = it
            }
        }

        val evidence = BoundedFailureEvidence.capture(root)

        assertThat(evidence.truncated).isTrue()
        assertThat(evidence.text).contains("suppressed-0", "cause-0", "[truncated]")
        assertThat(evidence.text).doesNotContain("suppressed-99", "cause-99", "Owner.m999")
        assertThat(
            evidence.text.toByteArray(Charsets.UTF_8).size,
        ).isLessThanOrEqualTo(BoundedFailureEvidence.MAX_UTF8_BYTES)
    }

    @Test
    fun `broken failure getter marks evidence unavailable`() {
        val failure = object : RuntimeException() {
            override val message: String get() = throw IllegalStateException("unavailable")
        }
        val evidence = BoundedFailureEvidence.capture(failure)
        assertThat(evidence.unavailable).isTrue()
        assertThat(evidence.text).contains("[unavailable]")
    }

    @Test
    fun `fatal and cancellation during evidence capture propagate unchanged`() {
        listOf(CancellationException("cancel"), LinkageError("fatal")).forEach { thrown ->
            val failure = object : RuntimeException() {
                override val message: String get() = throw thrown
            }
            assertThatThrownBy { BoundedFailureEvidence.capture(failure) }.isSameAs(thrown)
        }
    }
}

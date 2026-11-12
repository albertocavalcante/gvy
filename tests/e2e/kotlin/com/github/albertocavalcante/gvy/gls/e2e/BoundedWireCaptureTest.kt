package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BoundedWireCaptureTest {
    @Test
    fun `incoming bytes preserve literal envelopes and missing versus null fields`() {
        val bodies = listOf(
            """{"jsonrpc":"2.0","id":9007199254740993,"result":{"left":null}}""",
            """{"jsonrpc":"2.0","id":"02","error":{"code":-32601,"message":"missing"}}""",
            """{"jsonrpc":"2.0","method":"custom/note","params":null}""",
            """{"jsonrpc":"2.0","method":"custom/note"}""",
        )
        val original = bodies.joinToString("") { frame(it) }.toByteArray()
        val capture = BoundedWireCapture(original.size)
        val input = capture.input(ByteArrayInputStream(original))

        assertThat(input.readBytes()).isEqualTo(original)

        val evidence = capture.snapshot().received
        assertThat(evidence.bytes()).isEqualTo(original)
        assertThat(evidence.transferredBytes).isEqualTo(original.size.toLong())
        assertThat(evidence.losslessSoFar).isTrue()
        assertThat(evidence.endOfInputObserved).isTrue()
        assertThat(capture.snapshot().transmitted.retainedBytes).isZero()
    }

    @Test
    fun `outgoing writes retain byte exact UTF8 without double capture`() {
        val original = frame("""{"jsonrpc":"2.0","id":"é😀","result":{"right":[null]}}""").toByteArray()
        val delegate = ByteArrayOutputStream()
        val capture = BoundedWireCapture(original.size)
        capture.output(delegate).use { output ->
            output.write(original[0].toInt())
            output.write(original, 1, original.size - 1)
            output.flush()
        }

        assertThat(delegate.toByteArray()).isEqualTo(original)
        assertThat(capture.snapshot().transmitted.bytes()).isEqualTo(original)
        assertThat(capture.snapshot().transmitted.losslessSoFar).isTrue()
        assertThat(capture.snapshot().transmitted.endOfInputObserved).isFalse()
    }

    @Test
    fun `byte budget truncates evidence without truncating transport or replacing old snapshots`() {
        val capture = BoundedWireCapture(3)
        val delegate = ByteArrayOutputStream()
        val output = capture.output(delegate)
        output.write(byteArrayOf(1, 2, 3))
        val before = capture.snapshot()
        output.write(byteArrayOf(4, 5))
        output.write(6)

        assertThat(delegate.toByteArray()).isEqualTo(byteArrayOf(1, 2, 3, 4, 5, 6))
        assertThat(before.transmitted.bytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(before.transmitted.losslessSoFar).isTrue()
        val after = capture.snapshot().transmitted
        assertThat(after.bytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(after.transferredBytes).isEqualTo(6)
        assertThat(after.gaps).containsExactly(WireEvidenceGap.BYTE_LIMIT)
        assertThat(after.losslessSoFar).isFalse()
        assertThatThrownBy {
            (after.gaps as MutableSet).clear()
        }.isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun `overflow in the middle of a multibyte value keeps original bytes`() {
        val bytes = "é😀".toByteArray()
        val capture = BoundedWireCapture(3)
        assertThat(capture.input(ByteArrayInputStream(bytes)).readBytes()).isEqualTo(bytes)
        assertThat(capture.snapshot().received.bytes()).isEqualTo(bytes.copyOf(3))
        assertThat(capture.snapshot().received.gaps).containsExactly(WireEvidenceGap.BYTE_LIMIT)
    }

    @Test
    fun `skipped bytes create a sticky gap instead of joining unrelated byte ranges`() {
        val capture = BoundedWireCapture(20)
        val input = capture.input(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))
        assertThat(input.read()).isEqualTo(1)
        assertThat(input.skip(2)).isEqualTo(2)
        assertThat(input.read()).isEqualTo(4)
        assertThat(capture.snapshot().received.bytes()).isEqualTo(byteArrayOf(1))
        assertThat(capture.snapshot().received.transferredBytes).isEqualTo(4)
        assertThat(capture.snapshot().received.gaps).containsExactly(WireEvidenceGap.SKIPPED_BYTES)
    }

    @Test
    fun `mark and reset delegate faithfully and label replayed bytes incomplete`() {
        val capture = BoundedWireCapture(20)
        val input = capture.input(ByteArrayInputStream(byteArrayOf(1, 2)))
        assertThat(input.markSupported()).isTrue()
        input.mark(2)
        assertThat(input.read()).isEqualTo(1)
        input.reset()
        assertThat(input.readBytes()).isEqualTo(byteArrayOf(1, 2))
        assertThat(capture.snapshot().received.bytes()).isEqualTo(byteArrayOf(1))
        assertThat(capture.snapshot().received.gaps).containsExactly(WireEvidenceGap.STREAM_RESET)
    }

    @Test
    fun `read failure preserves identity and prevents a later read appearing contiguous`() {
        val failure = IOException("read failed")
        var calls = 0
        val capture = BoundedWireCapture(20)
        val input = capture.input(object : InputStream() {
            override fun read(): Int = when (calls++) {
                0 -> 1
                1 -> throw failure
                else -> 3
            }
        })
        assertThat(input.read()).isEqualTo(1)
        assertThatThrownBy { input.read() }.isSameAs(failure)
        assertThat(input.read()).isEqualTo(3)
        assertThat(capture.snapshot().received.bytes()).isEqualTo(byteArrayOf(1))
        assertThat(capture.snapshot().received.gaps).containsExactly(WireEvidenceGap.DELEGATE_FAILURE)
    }

    @Test
    fun `partial failed write is indeterminate and never claimed as a completed write`() {
        val failure = IOException("partial write")
        val delegate = ByteArrayOutputStream()
        val capture = BoundedWireCapture(20)
        val output = capture.output(object : OutputStream() {
            override fun write(value: Int) {
                delegate.write(value)
                throw failure
            }
        })
        assertThatThrownBy { output.write(byteArrayOf(1, 2, 3)) }.isSameAs(failure)
        assertThat(delegate.toByteArray()).isEqualTo(byteArrayOf(1))
        assertThat(capture.snapshot().transmitted.retainedBytes).isZero()
        assertThat(capture.snapshot().transmitted.transferredBytes).isZero()
        assertThat(capture.snapshot().transmitted.gaps).containsExactly(WireEvidenceGap.DELEGATE_FAILURE)
    }

    @Test
    fun `a delegate mutating a write buffer cannot fabricate lossless evidence`() {
        val sent = ByteArrayOutputStream()
        val capture = BoundedWireCapture(20)
        val output = capture.output(object : OutputStream() {
            override fun write(value: Int) = sent.write(value)

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                sent.write(bytes, offset, length)
                bytes.fill(9, offset, offset + length)
            }
        })
        output.write(byteArrayOf(1, 2, 3))
        assertThat(sent.toByteArray()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(capture.snapshot().transmitted.retainedBytes).isZero()
        assertThat(capture.snapshot().transmitted.transferredBytes).isEqualTo(3)
        assertThat(capture.snapshot().transmitted.gaps).containsExactly(WireEvidenceGap.MUTATED_WRITE_BUFFER)
        assertThat(capture.snapshot().transmitted.losslessSoFar).isFalse()
    }

    @Test
    fun `fatal delegate failure is rethrown without retaining its graph`() {
        val failure = LinkageError("fatal control")
        val capture = BoundedWireCapture(20)
        val output = capture.output(object : OutputStream() {
            override fun write(value: Int): Unit = throw failure
        })
        assertThatThrownBy { output.write(1) }.isSameAs(failure)
        assertThat(capture.snapshot().transmitted.gaps).containsExactly(WireEvidenceGap.DELEGATE_FAILURE)
    }

    @Test
    fun `overlapping operations are flagged without holding bookkeeping locks during IO`() {
        val entered = CountDownLatch(1)
        val bothEntered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val capture = BoundedWireCapture(20)
        val input = capture.input(object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                bothEntered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return 1
            }
        })
        try {
            val first = CompletableFuture.supplyAsync({ input.read() }, executor)
            check(entered.await(5, TimeUnit.SECONDS))
            // Snapshot and a second operation must not wait for the first delegate to return.
            assertThat(capture.snapshot().received.retainedBytes).isZero()
            assertThat(capture.snapshot().received.inFlightOperations).isEqualTo(1)
            assertThat(input.available()).isZero()
            val second = CompletableFuture.supplyAsync({ input.read() }, executor)
            try {
                check(bothEntered.await(5, TimeUnit.SECONDS))
                assertThat(capture.snapshot().received.gaps).contains(WireEvidenceGap.CONCURRENT_ACCESS)
            } finally {
                release.countDown()
            }
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(1)
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1)
            assertThat(capture.snapshot().received.losslessSoFar).isFalse()
            assertThat(capture.snapshot().received.bytes()).isEmpty()
        } finally {
            release.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `invalid budgets are rejected before allocating buffers`() {
        listOf(0, -1, Int.MAX_VALUE).forEach { limit ->
            assertThatThrownBy { BoundedWireCapture(limit) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `byte counts saturate and retain overflow as incomplete evidence`() {
        val capture = BoundedWireCapture(1)
        val input = capture.input(object : InputStream() {
            override fun read(): Int = 1
            override fun skip(count: Long): Long = count
        })
        assertThat(input.skip(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE)
        assertThat(input.read()).isEqualTo(1)
        val evidence = capture.snapshot().received
        assertThat(evidence.transferredBytes).isEqualTo(Long.MAX_VALUE)
        assertThat(evidence.gaps).containsExactly(WireEvidenceGap.SKIPPED_BYTES, WireEvidenceGap.BYTE_COUNT_OVERFLOW)
    }

    @Test
    fun `zero length operations do not overflow an exactly full prefix`() {
        val capture = BoundedWireCapture(1)
        val output = capture.output(ByteArrayOutputStream())
        output.write(1)
        output.write(byteArrayOf(), 0, 0)
        assertThat(capture.snapshot().transmitted.losslessSoFar).isTrue()
        assertThat(capture.snapshot().transmitted.bytes()).isEqualTo(byteArrayOf(1))
    }

    @Test
    fun `flush and close propagate the delegates exact failures`() {
        val flushFailure = IOException("flush")
        val closeFailure = IOException("close")
        val capture = BoundedWireCapture(1)
        val output = capture.output(object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun flush(): Unit = throw flushFailure
            override fun close(): Unit = throw closeFailure
        })
        assertThatThrownBy { output.flush() }.isSameAs(flushFailure)
        assertThatThrownBy { output.close() }.isSameAs(closeFailure)
        val input = capture.input(object : InputStream() {
            override fun read(): Int = -1
            override fun close(): Unit = throw closeFailure
        })
        assertThatThrownBy { input.close() }.isSameAs(closeFailure)
        assertThat(capture.snapshot().received.gaps).containsExactly(WireEvidenceGap.DELEGATE_FAILURE)
        assertThat(capture.snapshot().transmitted.gaps).containsExactly(WireEvidenceGap.DELEGATE_FAILURE)
    }

    @Test
    fun `a permissive delegate cannot make invalid bounds look like observed bytes`() {
        val capture = BoundedWireCapture(2)
        val output = capture.output(object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        })
        output.write(byteArrayOf(1), -1, -1)
        assertThat(capture.snapshot().transmitted.transferredBytes).isZero()
        assertThat(capture.snapshot().transmitted.gaps).containsExactly(WireEvidenceGap.DELEGATE_CONTRACT)
    }

    @Test
    fun `an invalid or zero length read cannot fabricate lossless EOF`() {
        listOf(-1 to 1, 0 to 0).forEach { (offset, length) ->
            val capture = BoundedWireCapture(2)
            val input = capture.input(object : InputStream() {
                override fun read(): Int = -1
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = -1
            })
            assertThat(input.read(byteArrayOf(1), offset, length)).isEqualTo(-1)
            assertThat(capture.snapshot().received.gaps).containsExactly(WireEvidenceGap.DELEGATE_CONTRACT)
            assertThat(capture.snapshot().received.losslessSoFar).isFalse()
            assertThat(capture.snapshot().received.endOfInputObserved).isFalse()
        }
    }

    private fun frame(body: String): String = "Content-Length: ${body.toByteArray().size}\r\n\r\n$body"

    private fun WireStreamEvidence.bytes(): ByteArray = Base64.getDecoder().decode(prefixBase64)
}

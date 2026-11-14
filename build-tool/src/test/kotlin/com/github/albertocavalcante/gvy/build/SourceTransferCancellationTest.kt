// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import com.github.albertocavalcante.gvy.build.maven.SourceTransferCancellation
import org.eclipse.aether.spi.connector.transport.GetTask
import org.eclipse.aether.spi.connector.transport.PeekTask
import org.eclipse.aether.spi.connector.transport.PutTask
import org.eclipse.aether.spi.connector.transport.Transporter
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceTransferCancellationTest {
    @Test
    fun `registration after cancellation immediately closes transport`() {
        val cancellation = SourceTransferCancellation()
        cancellation.cancel()
        val transport = CountingTransporter()
        val registered = cancellation.register(transport)
        assertEquals(1, transport.closes.get())
        registered.close()
        cancellation.cancel()
        assertEquals(1, transport.closes.get())
    }

    @Test
    fun `registration racing cancellation never loses a transport`() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val cancellation = SourceTransferCancellation()
                val transport = CountingTransporter()
                val start = CyclicBarrier(2)
                val register = workers.submit<Transporter> {
                    start.await(2, TimeUnit.SECONDS)
                    cancellation.register(transport)
                }
                val cancel = workers.submit {
                    start.await(2, TimeUnit.SECONDS)
                    cancellation.cancel()
                }
                val registered = register.get(2, TimeUnit.SECONDS)
                cancel.get(2, TimeUnit.SECONDS)
                assertEquals(1, transport.closes.get())
                registered.close()
                assertEquals(1, transport.closes.get())
            }
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `cancelling one registry leaves other operations open`() {
        val first = SourceTransferCancellation()
        val second = SourceTransferCancellation()
        val firstTransport = CountingTransporter()
        val secondTransport = CountingTransporter()
        first.register(firstTransport)
        val registeredSecond = second.register(secondTransport)
        first.cancel()
        assertEquals(1, firstTransport.closes.get())
        assertEquals(0, secondTransport.closes.get())
        registeredSecond.close()
        second.cancel()
        assertEquals(1, secondTransport.closes.get())
    }

    @Test
    fun `a transport close failure does not prevent closing remaining transports`() {
        val cancellation = SourceTransferCancellation()
        val failingTransport = CountingTransporter { throw IOException("close failed") }
        val remainingTransport = CountingTransporter()
        cancellation.register(failingTransport)
        cancellation.register(remainingTransport)

        cancellation.cancel()

        assertEquals(1, failingTransport.closes.get())
        assertEquals(1, remainingTransport.closes.get())
    }
}

private class CountingTransporter(private val onClose: () -> Unit = {}) : Transporter {
    val closes = AtomicInteger()
    override fun close() {
        closes.incrementAndGet()
        onClose()
    }
    override fun classify(error: Throwable): Int = Transporter.ERROR_OTHER
    override fun peek(task: PeekTask): Unit = error("No transfer expected")
    override fun get(task: GetTask): Unit = error("No transfer expected")
    override fun put(task: PutTask): Unit = error("No transfer expected")
}

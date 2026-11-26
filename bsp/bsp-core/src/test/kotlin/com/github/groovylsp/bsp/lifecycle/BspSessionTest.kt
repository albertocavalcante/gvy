// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.groovylsp.bsp.lifecycle

import com.github.groovylsp.bsp.client.BuildServerConnection
import com.github.groovylsp.bsp.model.BuildTargetCache
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val ATTEMPTS = 100
private const val TIMEOUT_SECONDS = 60L

class BspSessionTest {

    /** A server process that stays alive and counts how often the session kills it. */
    private class CountingProcess : Process() {
        val destroyCalls = AtomicInteger()

        override fun isAlive(): Boolean = true
        override fun destroyForcibly(): Process {
            destroyCalls.incrementAndGet()
            return this
        }
        override fun destroy() = Unit
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = InputStream.nullInputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
    }

    private val connection = mockk<BuildServerConnection>(relaxed = true)

    private fun session(process: Process) = BspSession(connection, BuildTargetCache(), BspClientHandler(), process)

    @Test
    fun `close stops the server process once and deactivates the session`() {
        val process = CountingProcess()
        val session = session(process)

        session.close()
        session.close()

        assertEquals(1, process.destroyCalls.get())
        assertFalse(session.isActive())
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `racing closes stop the server process once per session`() {
        // A check-then-set close lets both racers through when they pass the check together; each attempt releases
        // two threads at once to give that window every chance.
        val process = CountingProcess()

        repeat(ATTEMPTS) {
            val session = session(process)
            val barrier = CyclicBarrier(2)
            val racers = List(2) {
                Thread {
                    barrier.await()
                    session.close()
                }
            }
            racers.forEach(Thread::start)
            racers.forEach(Thread::join)
        }

        assertEquals(ATTEMPTS, process.destroyCalls.get())
    }
}

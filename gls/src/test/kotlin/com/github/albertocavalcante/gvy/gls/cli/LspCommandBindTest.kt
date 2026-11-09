package com.github.albertocavalcante.gvy.gls.cli

import com.github.ajalt.clikt.core.ProgramResult
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LspCommandBindTest {
    @Test
    fun `binds to loopback by default`() {
        bindServerSocket(0).use {
            assertTrue(it.inetAddress.isLoopbackAddress, "expected loopback but was ${it.inetAddress}")
        }
    }

    @Test
    fun `explicit non-loopback host is honoured`() {
        bindServerSocket(0, "0.0.0.0").use {
            assertTrue(it.inetAddress.isAnyLocalAddress, "expected wildcard but was ${it.inetAddress}")
            assertFalse(it.inetAddress.isLoopbackAddress)
        }
    }

    @Test
    fun `explicit ipv6 loopback host is honoured`() {
        val socket = runCatching { bindServerSocket(0, "::1") }.getOrNull() ?: return // no IPv6 on this host
        socket.use { assertEquals(InetAddress.getByName("::1"), it.inetAddress) }
    }

    @Test
    fun `unresolvable host fails with a clear exit`() {
        val error = assertFailsWith<ProgramResult> { LspCommand().runSocket(0, "no-such-host.invalid") }
        assertEquals(1, error.statusCode)
    }
}

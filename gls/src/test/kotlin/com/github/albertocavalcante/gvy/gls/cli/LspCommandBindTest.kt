package com.github.albertocavalcante.gvy.gls.cli

import com.github.ajalt.clikt.core.ProgramResult
import org.junit.jupiter.api.Assumptions
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
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
        val ipv6Available = runCatching { ServerSocket(0, 1, InetAddress.getByName("::1")).close() }.isSuccess
        Assumptions.assumeTrue(ipv6Available, "IPv6 loopback is not available on this host")

        bindServerSocket(0, "::1").use { assertEquals(InetAddress.getByName("::1"), it.inetAddress) }
    }

    @Test
    fun `unresolvable host is rejected with UnknownHostException`() {
        assertFailsWith<UnknownHostException> { bindServerSocket(0, "no-such-host.invalid") }
    }

    @Test
    fun `socket command exits with status 1 for an unresolvable host`() {
        val error = assertFailsWith<ProgramResult> { LspCommand().runSocket(0, "no-such-host.invalid") }
        assertEquals(1, error.statusCode)
    }
}

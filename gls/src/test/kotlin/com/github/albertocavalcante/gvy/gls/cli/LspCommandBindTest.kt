package com.github.albertocavalcante.gvy.gls.cli

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LspCommandBindTest {
    @Test
    fun `binds to loopback by default`() {
        bindServerSocket(0).use {
            assertTrue(it.inetAddress.isLoopbackAddress, "expected loopback but was ${it.inetAddress}")
        }
    }

    @Test
    fun `explicit host is honoured`() {
        bindServerSocket(0, "127.0.0.1").use {
            assertEquals(InetAddress.getByName("127.0.0.1"), it.inetAddress)
        }
    }
}

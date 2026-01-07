package com.github.groovylsp.bsp.client

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConnectionConfigTest {

    @Test
    fun `default config has sensible timeout values`() {
        val config = ConnectionConfig()

        assertEquals(60.seconds, config.initTimeout)
        assertEquals(120.seconds, config.requestTimeout)
        assertEquals(10.seconds, config.shutdownTimeout)
    }

    @Test
    fun `can customize all timeout values`() {
        val config = ConnectionConfig(
            initTimeout = 30.seconds,
            requestTimeout = 60.seconds,
            shutdownTimeout = 5.seconds,
        )

        assertEquals(30.seconds, config.initTimeout)
        assertEquals(60.seconds, config.requestTimeout)
        assertEquals(5.seconds, config.shutdownTimeout)
    }

    @Test
    fun `supports millisecond precision for timeouts`() {
        val config = ConnectionConfig(
            initTimeout = 500.milliseconds,
            requestTimeout = 1500.milliseconds,
            shutdownTimeout = 100.milliseconds,
        )

        assertEquals(500.milliseconds, config.initTimeout)
        assertEquals(1500.milliseconds, config.requestTimeout)
        assertEquals(100.milliseconds, config.shutdownTimeout)
    }

    @Test
    fun `data class provides equality comparison`() {
        val config1 = ConnectionConfig(
            initTimeout = 30.seconds,
            requestTimeout = 60.seconds,
            shutdownTimeout = 5.seconds,
        )
        val config2 = ConnectionConfig(
            initTimeout = 30.seconds,
            requestTimeout = 60.seconds,
            shutdownTimeout = 5.seconds,
        )

        assertEquals(config1, config2)
        assertEquals(config1.hashCode(), config2.hashCode())
    }

    @Test
    fun `data class provides copy functionality`() {
        val original = ConnectionConfig()
        val modified = original.copy(requestTimeout = 180.seconds)

        assertEquals(60.seconds, original.initTimeout)
        assertEquals(120.seconds, original.requestTimeout)
        assertEquals(10.seconds, original.shutdownTimeout)

        assertEquals(60.seconds, modified.initTimeout)
        assertEquals(180.seconds, modified.requestTimeout)
        assertEquals(10.seconds, modified.shutdownTimeout)
    }
}

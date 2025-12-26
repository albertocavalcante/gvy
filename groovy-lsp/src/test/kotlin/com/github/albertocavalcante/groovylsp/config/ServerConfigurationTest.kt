package com.github.albertocavalcante.groovylsp.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerConfigurationTest {

    @Test
    fun `parse groovy language version override`() {
        val config = ServerConfiguration.fromMap(
            mapOf(
                "groovy.language.version" to "3.0.9",
            ),
        )

        assertEquals("3.0.9", config.groovyLanguageVersion)
    }

    @Test
    fun `missing language version keeps default`() {
        val config = ServerConfiguration.fromMap(emptyMap())

        assertNull(config.groovyLanguageVersion)
    }
}

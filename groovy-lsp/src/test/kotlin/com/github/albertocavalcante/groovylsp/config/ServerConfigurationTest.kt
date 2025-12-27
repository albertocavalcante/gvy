package com.github.albertocavalcante.groovylsp.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `parse worker descriptors from config`() {
        val config = ServerConfiguration.fromMap(
            mapOf(
                "groovy.workers" to listOf(
                    mapOf(
                        "id" to "legacy",
                        "minVersion" to "2.0.0",
                        "maxVersion" to "2.4.0",
                        "features" to listOf("ast"),
                        "connector" to "in-process",
                    ),
                ),
            ),
        )

        assertEquals(1, config.workerDescriptors.size)
        val descriptor = config.workerDescriptors.single()
        assertEquals("legacy", descriptor.id)
        assertEquals("2.0.0", descriptor.minVersion)
        assertEquals("2.4.0", descriptor.maxVersion)
        assertTrue(descriptor.features.contains("ast"))
        assertEquals("in-process", descriptor.connector)
    }
}

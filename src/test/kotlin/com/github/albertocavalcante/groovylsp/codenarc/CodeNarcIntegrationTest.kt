package com.github.albertocavalcante.groovylsp.codenarc

import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.net.URI
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Basic integration test for CodeNarc functionality.
 */
class CodeNarcIntegrationTest {

    @Test
    fun `should create CodeNarc service without errors`() {
        val configProvider = mockk<ConfigurationProvider> {
            every { getServerConfiguration() } returns ServerConfiguration()
            every { getWorkspaceRoot() } returns null
        }

        assertDoesNotThrow {
            CodeNarcService(configProvider)
        }
    }

    @Test
    fun `should handle empty source code`() = runTest {
        val configProvider = mockk<ConfigurationProvider> {
            every { getServerConfiguration() } returns ServerConfiguration()
            every { getWorkspaceRoot() } returns null
        }
        val service = CodeNarcService(configProvider)
        val uri = URI.create("file:///test.groovy")

        val violations = service.analyzeString("", uri)

        assertNotNull(violations)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `should handle blank source code`() = runTest {
        val configProvider = mockk<ConfigurationProvider> {
            every { getServerConfiguration() } returns ServerConfiguration()
            every { getWorkspaceRoot() } returns null
        }
        val service = CodeNarcService(configProvider)
        val uri = URI.create("file:///test.groovy")

        val violations = service.analyzeString("   \n\t\n  ", uri)

        assertNotNull(violations)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `should analyze simple Groovy code without throwing exceptions`() = runTest {
        val configProvider = mockk<ConfigurationProvider> {
            every { getServerConfiguration() } returns ServerConfiguration()
            every { getWorkspaceRoot() } returns null
        }
        val service = CodeNarcService(configProvider)
        val uri = URI.create("file:///test.groovy")
        val source = """
            def hello() {
                println "Hello World"
            }
        """.trimIndent()

        assertDoesNotThrow {
            val violations = service.analyzeString(source, uri)
            assertNotNull(violations)
        }
    }

    @Test
    fun `should create diagnostic converter without errors`() {
        assertDoesNotThrow {
            CodeNarcDiagnosticConverter
        }
    }

    @Test
    fun `should create configuration manager without errors`() {
        assertDoesNotThrow {
            CodeNarcConfigurationManager()
        }
    }

    @Test
    fun `should load default configuration`() {
        val configManager = CodeNarcConfigurationManager()

        assertDoesNotThrow {
            val config = configManager.loadConfiguration(null)
            assertNotNull(config)
            assertNotNull(config.ruleSetString)
            assertTrue(config.ruleSetString.isNotEmpty())
        }
    }
}

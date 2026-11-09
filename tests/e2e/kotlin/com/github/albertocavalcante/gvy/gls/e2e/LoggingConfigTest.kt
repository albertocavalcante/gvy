package com.github.albertocavalcante.gvy.gls.e2e

import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the E2E Logback configuration. The harness forwards the server's stderr through its own
 * INFO logger (see [LanguageServerSessionFactory]); if the configured logger names drift from the
 * real packages, the root WARN level hides every server line from CI logs and uploaded reports.
 */
class LoggingConfigTest {
    @Test
    fun `harness logger forwards server stderr at INFO`() {
        val logger = LoggerFactory.getLogger(LanguageServerSessionFactory::class.java)
        assertTrue(logger.isInfoEnabled, "INFO disabled for ${logger.name}; server stderr would be hidden")
    }

    @Test
    fun `server packages log at INFO for in-process runs`() {
        val logger = LoggerFactory.getLogger("com.github.albertocavalcante.gvy.gls.GroovyLanguageServer")
        assertTrue(logger.isInfoEnabled, "INFO disabled for ${logger.name}")
    }

    @Test
    fun `third party dependencies stay at WARN`() {
        val logger = LoggerFactory.getLogger("org.eclipse.lsp4j.jsonrpc.RemoteEndpoint")
        assertFalse(logger.isInfoEnabled, "Dependency verbosity should stay bounded")
    }
}

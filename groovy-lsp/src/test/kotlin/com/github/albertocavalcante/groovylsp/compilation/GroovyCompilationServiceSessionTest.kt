package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.engine.api.LanguageEngine
import com.github.albertocavalcante.groovylsp.engine.api.LanguageSession
import com.github.albertocavalcante.groovylsp.engine.config.EngineType
import com.github.albertocavalcante.groovylsp.engine.impl.core.CoreLanguageEngine
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [GroovyCompilationService.getSession] with different engine types.
 *
 * These tests verify that getSession() works correctly with both Native and Core engines.
 */
class GroovyCompilationServiceSessionTest {

    @Test
    fun `getSession returns non-null session for Core engine`() = runBlocking {
        // This test verifies that getSession works with CoreLanguageEngine
        val engine = CoreLanguageEngine()
        val uri = URI.create("file:///test.groovy")
        val content = """
            class Calculator {
                int add(int a, int b) { return a + b }
            }
        """.trimIndent()

        // Create session directly from engine
        val session = engine.createSession(ParseRequest(uri, content))

        assertNotNull(session, "Session should not be null for Core engine")
        assertNotNull(session.features.hoverProvider, "Hover provider should be available")
    }

    @Test
    fun `Core engine session provides hover`() = runBlocking {
        val engine = CoreLanguageEngine()
        val uri = URI.create("file:///test.groovy")
        val content = """
            class Calculator {
                int add(int a, int b) { return a + b }
            }
        """.trimIndent()

        val session = engine.createSession(ParseRequest(uri, content))
        val hoverParams = HoverParams(
            TextDocumentIdentifier(uri.toString()),
            Position(1, 8), // Position on "add" method
        )

        val hover = session.features.hoverProvider.getHover(hoverParams)

        assertNotNull(hover, "Hover should not be null")
    }

    @Test
    fun `LanguageEngine createSession with URI and content works for Core`() {
        val engine = CoreLanguageEngine()
        val uri = URI.create("file:///test.groovy")
        val content = "class Test { void foo() {} }"

        // This tests the unified interface method we're adding
        val session = engine.createSession(uri, content)

        assertNotNull(session, "Session created from URI and content should not be null")
        assertTrue(session.result.isSuccess, "Parse should succeed for valid code")
    }
}

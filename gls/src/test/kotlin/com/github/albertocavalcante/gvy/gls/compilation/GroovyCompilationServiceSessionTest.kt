// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.engine.EngineFactory
import com.github.albertocavalcante.gvy.gls.engine.config.EngineConfiguration
import com.github.albertocavalcante.gvy.gls.engine.config.EngineType
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.nativeapi.ParseRequest
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The saved Core setting retains the public session API through the Native engine. */
class GroovyCompilationServiceSessionTest {
    private val documents = DocumentProvider()
    private val compilation = GroovyCompilationService(documentProvider = documents)
    private val engine = EngineFactory.create(
        config = EngineConfiguration(EngineType.fromString("core")),
        parser = GroovyParserFacade(),
        compilationService = compilation,
        documentProvider = documents,
        sourceNavigator = null,
    )
    private val uri = URI.create("file:///Calculator.groovy")
    private val content = """
        class Calculator {
            int add(int a, int b) { return a + b }
        }
    """.trimIndent()

    @AfterEach
    fun closeCompilation() {
        compilation.shutdown()
    }

    @Test
    fun `legacy core alias creates a session with hover support`() {
        val session = engine.createSession(ParseRequest(uri, content))

        assertTrue(session.result.isSuccess)
        assertNotNull(session.features.hoverProvider)
    }

    @Test
    fun `legacy core alias session provides Native method hover`(): Unit = runBlocking {
        documents.put(uri, content, 1)
        assertTrue(compilation.compile(uri, content).isSuccess)
        val session = engine.createSession(ParseRequest(uri, content))
        val params = HoverParams(TextDocumentIdentifier(uri.toString()), Position(1, 8))

        val hover = session.features.hoverProvider.getHover(params)

        assertContains(hover.contents.right.value, "int add(int a, int b)")
    }

    @Test
    fun `legacy core alias supports session creation from URI and content`() {
        val session = engine.createSession(uri, content)

        assertTrue(session.result.isSuccess)
        assertNotNull(session.features.documentSymbolProvider)
    }
}

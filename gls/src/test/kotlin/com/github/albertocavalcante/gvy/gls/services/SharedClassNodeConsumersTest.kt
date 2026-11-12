// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.semantictokens.GroovySemanticTokenProvider
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Consumers beyond the position index must not read the JVM-wide ClassHelper nodes' stamped positions: a file
 * importing `groovy.lang.Script` at line 4, columns 9+, must leave other documents untouched (#1354).
 */
class SharedClassNodeConsumersTest {
    private val stamping = URI.create("file:///Stamp.groovy")
    private val script = URI.create("file:///Victim.groovy")
    private val scriptText = "println 1\nprintln 2\nprintln 3\n        timeout(time: 1)\n"
    private val compilation = GroovyCompilationService()
    private val documents = DocumentProvider()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val service = GroovyTextDocumentService(
        scope,
        compilation,
        GroovyTextDocumentServiceOptions(documentProvider = documents),
    )

    @AfterEach
    fun close() {
        scope.cancel()
        compilation.clearCaches()
    }

    private fun stampScriptType() = runBlocking {
        compilation.compile(stamping, "// a\n// b\n// c\n        import groovy.lang.Script\nclass Stamp {}\n")
    }

    @Test
    fun `semantic tokens of another script carry no class token at the stamped range`() {
        stampScriptType()
        documents.put(script, scriptText, 1)

        val tokens = tokens()

        val classAtStamp = tokens.filter {
            it.line == 3 && it.start == 8 && it.type == GroovySemanticTokenProvider.TokenTypes.CLASS
        }
        assertEquals(emptyList(), classAtStamp, "a CLASS token painted from the shared Script node")
    }

    @Test
    fun `the semantic document of another script has no reference to the stamped Script node`() = runBlocking {
        stampScriptType()
        compilation.compile(script, scriptText)

        val document = SemanticDocumentBuilder(checkNotNull(compilation.getAst(script) as? ModuleNode), script).build()

        val foreign = document.occurrences.filter { it.range.startLine == 3 && it.range.startColumn == 8 }
        assertEquals(emptyList(), foreign, "an occurrence placed at the other file's stamped Script range")
    }

    @Test
    fun `import java util List still resolves at its own name`() = runBlocking {
        val uri = URI.create("file:///Imports.groovy")
        compilation.compile(uri, "package p\n\nimport java.util.List\nclass Imports {}\n")

        val node = compilation.getAstModel(uri)?.getNodeAt(uri, 2, 17)

        assertTrue(node is ClassNode && node.name == "java.util.List", "import target: $node")
    }

    private data class Token(val line: Int, val start: Int, val length: Int, val type: Int)

    private fun tokens(): List<Token> {
        val result = service.semanticTokensFull(
            SemanticTokensParams().apply { textDocument = TextDocumentIdentifier(script.toString()) },
        ).get(5, TimeUnit.SECONDS)
        var line = 0
        var start = 0
        return result.data.chunked(5).map { raw ->
            start = if (raw[0] == 0) start + raw[1] else raw[1]
            line += raw[0]
            Token(line, start, raw[2], raw[3])
        }
    }
}

// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.semantictokens

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticTokensSourcePositionTest {
    private val uri = URI.create("file:///SemanticPositions.groovy")
    private val compilation = GroovyCompilationService()
    private val documents = DocumentProvider()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val service = GroovyTextDocumentService(
        scope,
        compilation,
        GroovyTextDocumentServiceOptions(documentProvider = documents),
    )
    private data class Token(val line: Int, val start: Int, val length: Int, val type: Int)

    @AfterEach
    fun close() {
        scope.cancel()
        compilation.clearCaches()
    }

    @Test
    fun `class tokens cover the declaration identifier rather than its keyword`() {
        expect(request("class Fresh {}\n"), Token(0, 6, 5, GroovySemanticTokenProvider.TokenTypes.CLASS))
    }

    @Test
    fun `supplementary text before a declaration preserves the UTF16 identifier column`() {
        expect(request("/* 😀 */ class Fresh {}\n"), Token(0, 15, 5, GroovySemanticTokenProvider.TokenTypes.CLASS))
    }

    @Test
    fun `class declarations after bare CR use protocol line and identifier coordinates`() {
        expect(request("// header\rclass Fresh {}\r"), Token(1, 6, 5, GroovySemanticTokenProvider.TokenTypes.CLASS))
    }

    @Test
    fun `field tokens cover the field name rather than its type`() {
        val source = "class Sample { String field; String echo(String value) { value } }\n"
        expect(request(source), Token(0, 22, 5, GroovySemanticTokenProvider.TokenTypes.PROPERTY))
    }

    @Test
    fun `ordinary method and parameter anchors remain exact`() {
        val source = "class Sample { String field; String echo(String value) { value } }\n"
        val tokens = request(source)
        expect(tokens, Token(0, 36, 4, GroovySemanticTokenProvider.TokenTypes.METHOD))
        expect(tokens, Token(0, 48, 5, GroovySemanticTokenProvider.TokenTypes.PARAMETER))
    }

    @Test
    fun `array type tokens cover the element type name, not a JVM descriptor`() {
        // `String[]` at 15 (return) and 30 (parameter); an array name such as `String;` was one char too long.
        val source = "class Sample { String[] split(String[] items) { items } }\n"
        val tokens = request(source)
        val typeTokens = tokens.filter { it.start == 15 || it.start == 30 }

        assertEquals(listOf(6, 6), typeTokens.map { it.length }, "found $tokens")
        expect(tokens, Token(0, 39, 5, GroovySemanticTokenProvider.TokenTypes.PARAMETER))
    }

    @Test
    fun `unaliased import tokens cover the imported simple name`() {
        expect(
            request("import java.util.List\nclass Sample {}\n"),
            Token(0, 17, 4, GroovySemanticTokenProvider.TokenTypes.CLASS),
        )
    }

    @Test
    fun `explicit import aliases remain highlighted at their declaration`() {
        expect(
            request("import java.util.List as Names\nclass Sample {}\n"),
            Token(0, 25, 5, GroovySemanticTokenProvider.TokenTypes.CLASS),
        )
    }

    @Test
    fun `same-line method annotations do not move names to the following line`() {
        val source = "class Sample {\n @Deprecated String echo(String value) { value }\n}\n"
        expect(request(source), Token(1, 20, 4, GroovySemanticTokenProvider.TokenTypes.METHOD))
    }

    @Test
    fun `parameter declarations after bare CR retain the correct physical line`() {
        val source = "class Sample {\r String echo(String value) { value }\r}\r"
        expect(request(source), Token(1, 20, 5, GroovySemanticTokenProvider.TokenTypes.PARAMETER))
    }

    @Test
    fun `UTF16 parameter coordinates are not converted twice`() {
        val source = "/* 😀 */ class Sample { String echo(String value) { value } }\n"
        expect(request(source), Token(0, source.indexOf("value"), 5, GroovySemanticTokenProvider.TokenTypes.PARAMETER))
    }

    @Test
    fun `Jenkins calls after supplementary text and bare CR have exact source coordinates`(): Unit = runBlocking {
        val source = "/* 😀 */\rpipeline { stage('Build') {} }\r"
        val tokens = jenkins(source)
        assertTrue(tokens.any { it.line == 1 && it.startChar == 0 && it.length == 8 })
        assertTrue(tokens.any { it.line == 1 && it.startChar == 11 && it.length == 5 })
    }

    @Test
    fun `ordinary Jenkins call coordinates remain exact`(): Unit = runBlocking {
        val tokens = jenkins("pipeline { stage('Build') {} }\n")
        assertTrue(tokens.any { it.line == 0 && it.startChar == 0 && it.length == 8 })
        assertTrue(tokens.any { it.line == 0 && it.startChar == 11 && it.length == 5 })
    }

    @Test
    fun `enum constants anchor their declaration before same-named constructor arguments`() {
        val source = "enum Choice { ITEM(ITEM()); static String ITEM(){ 'x' }; Choice(String v){} }\n"
        val members = request(source).filter { it.type == GroovySemanticTokenProvider.TokenTypes.ENUM_MEMBER }
        assertEquals(listOf(Token(0, 14, 4, GroovySemanticTokenProvider.TokenTypes.ENUM_MEMBER)), members)
    }

    @Test
    fun `ordinary Groovy keywords are highlighted without keywords inside comments and strings`() {
        val source = "class Fresh {\n def call() {\n  return 'class def return'\n }\n}\n// class def return\n"
        val keywords = request(source).filter { it.type == GroovySemanticTokenProvider.TokenTypes.KEYWORD }
        assertEquals(
            listOf(
                Token(0, 0, 5, GroovySemanticTokenProvider.TokenTypes.KEYWORD),
                Token(1, 1, 3, GroovySemanticTokenProvider.TokenTypes.KEYWORD),
                Token(2, 2, 6, GroovySemanticTokenProvider.TokenTypes.KEYWORD),
            ),
            keywords,
        )
    }

    @Test
    fun `keyword coordinates preserve supplementary prefixes and bare CR lines`() {
        val source = "/* 😀 */\rclass Fresh { def call() { return 1 } }\r"
        val keywords = request(source).filter { it.type == GroovySemanticTokenProvider.TokenTypes.KEYWORD }
        assertEquals(
            listOf(
                Token(1, 0, 5, GroovySemanticTokenProvider.TokenTypes.KEYWORD),
                Token(1, 14, 3, GroovySemanticTokenProvider.TokenTypes.KEYWORD),
                Token(1, 27, 6, GroovySemanticTokenProvider.TokenTypes.KEYWORD),
            ),
            keywords,
        )
    }

    @Test
    fun `keyword spellings used as map and named argument keys stay uncolored`() {
        val source = "def values = [class: 1, return: 2, def: 3]; call(return: 4)\n"
        val keywords = request(source).filter { it.type == GroovySemanticTokenProvider.TokenTypes.KEYWORD }
        assertEquals(listOf(Token(0, 0, 3, GroovySemanticTokenProvider.TokenTypes.KEYWORD)), keywords)
    }

    @Test
    fun `an unfinished block comment cannot introduce keyword tokens`() {
        val source = "class Good {}\n/* class Fake return"
        val keywords = request(source).filter { it.type == GroovySemanticTokenProvider.TokenTypes.KEYWORD }
        assertEquals(listOf(Token(0, 0, 5, GroovySemanticTokenProvider.TokenTypes.KEYWORD)), keywords)
    }

    private suspend fun jenkins(source: String): List<JenkinsSemanticTokenProvider.SemanticToken> {
        val parsed = compilation.compileTransient(uri, source)
        return JenkinsSemanticTokenProvider.tokensForSnapshot(
            SemanticTokenInput(parsed.astModel, uri, sourceText = source),
            true,
            emptySet(),
        ) {}
    }

    private fun request(source: String): List<Token> {
        documents.put(uri, source, 1)
        val result = service.semanticTokensFull(
            SemanticTokensParams().apply { textDocument = TextDocumentIdentifier(uri.toString()) },
        ).get(5, TimeUnit.SECONDS)
        assertEquals(0, result.data.size % 5)
        var line = 0
        var start = 0
        return result.data.chunked(5).map { raw ->
            start = if (raw[0] == 0) start + raw[1] else raw[1]
            line += raw[0]
            Token(line, start, raw[2], raw[3])
        }
    }

    private fun expect(tokens: List<Token>, expected: Token) {
        assertTrue(expected in tokens, "Expected $expected in $tokens")
    }
}

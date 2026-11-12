// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.semantictokens

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SemanticParameterRangeTest {
    @Test
    fun `typed parameter tokens select the identifier instead of its type`(): Unit = runBlocking {
        assertParameter("String value", "value", "value)")
    }

    @Test
    fun `primitive parameter identifiers have exact spans`(): Unit = runBlocking {
        assertParameter("int value", "value", "value)", type = "int" to TokenTypeResolver.TokenTypes.CLASS)
    }

    @Test
    fun `array parameter identifiers have exact spans`(): Unit = runBlocking {
        assertParameter("String[] values", "values", "values)")
    }

    @Test
    fun `vararg parameter identifiers have exact spans`(): Unit = runBlocking {
        assertParameter("String... values", "values", "values)")
    }

    @Test
    fun `qualified generic parameter identifiers have exact spans`(): Unit = runBlocking {
        assertParameter(
            "java.util.List<String> values",
            "values",
            "values)",
            type = "List" to TokenTypeResolver.TokenTypes.INTERFACE,
        )
    }

    @Test
    fun `interface array component tokens retain interface classification`(): Unit = runBlocking {
        assertParameter(
            "java.util.List<String>[] values",
            "values",
            "values)",
            type = "List" to TokenTypeResolver.TokenTypes.INTERFACE,
        )
    }

    @Test
    fun `interface vararg component tokens retain interface classification`(): Unit = runBlocking {
        assertParameter(
            "java.util.List... values",
            "values",
            "values)",
            type = "List" to TokenTypeResolver.TokenTypes.INTERFACE,
        )
    }

    @Test
    fun `enum array component tokens retain enum classification`(): Unit = runBlocking {
        assertParameter(
            "Shade[] values",
            "values",
            "values)",
            "enum Shade { BLUE }\n",
            type = "Shade" to TokenTypeResolver.TokenTypes.ENUM,
        )
    }

    @Test
    fun `a parameter named like its type selects the second identifier`(): Unit = runBlocking {
        assertParameter("String String", "String", "String)")
    }

    @Test
    fun `comments containing the parameter name do not become declarations`(): Unit = runBlocking {
        assertParameter("String /* value */ value", "value", "value)")
    }

    @Test
    fun `default values containing the parameter name do not become declarations`(): Unit = runBlocking {
        assertParameter("String value = 'value'", "value", "value =")
    }

    @Test
    fun `annotations and final modifiers do not shift the parameter token`(): Unit = runBlocking {
        assertParameter("@Deprecated final String value", "value", "value)")
    }

    @Test
    fun `slashy annotation strings containing quotes do not hide the parameter`(): Unit = runBlocking {
        assertParameter("@SuppressWarnings(/'/) String value", "value", "value)")
    }

    @Test
    fun `dollar slashy annotation strings containing quotes do not hide the parameter`(): Unit = runBlocking {
        assertParameter("@SuppressWarnings(\$/\"/\$) String value", "value", "value)")
    }

    @Test
    fun `slashy annotations do not hide dynamic parameters`(): Unit = runBlocking {
        assertParameter("@SuppressWarnings(/'/) def value", "value", "value)", type = null)
    }

    @Test
    fun `slashy annotations do not hide untyped parameters`(): Unit = runBlocking {
        assertParameter("@SuppressWarnings(/'/) value", "value", "value)", type = null)
    }

    @Test
    fun `comments after the identifier do not become declarations`(): Unit = runBlocking {
        assertParameter("String value /* value */ = 'value'", "value", "value /*")
    }

    @Test
    fun `multiline parameter declarations select their identifier line`(): Unit = runBlocking {
        assertParameter("String\n        value =\n        'value'", "value", "value =")
    }

    @Test
    fun `aliased parameter types do not overlap the parameter identifier`(): Unit = runBlocking {
        assertParameter(
            "Text value",
            "value",
            "value)",
            "import java.lang.String as Text\n",
            type = "Text" to TokenTypeResolver.TokenTypes.CLASS,
        )
    }

    @Test
    fun `unicode parameter identifiers have exact spans`(): Unit = runBlocking {
        assertParameter("String café", "café", "café)")
    }

    @Test
    fun `supplementary characters before a parameter preserve UTF16 spans`(): Unit = runBlocking {
        assertParameter("/* 😀 */ String value", "value", "value)")
    }

    private suspend fun assertParameter(
        declaration: String,
        name: String,
        marker: String,
        prefix: String = "",
        type: Pair<String, Int>? = "String" to TokenTypeResolver.TokenTypes.CLASS,
    ) {
        val source = prefix + "class Example {\n    String echo($declaration) { null }\n}\n"
        val uri = URI.create("file:///ParameterRanges.groovy")
        val service = GroovyCompilationService()
        try {
            val compiled = service.compile(uri, source)
            assertTrue(compiled.isSuccess, compiled.diagnostics.toString())
            val tokens = GroovySemanticTokenProvider.getSemanticTokens(
                assertNotNull(service.getAstModel(uri)),
                uri,
                moduleNode = service.getAst(uri) as? ModuleNode,
                sourceText = source,
            )
            val offset = source.indexOf(marker)
            assertTrue(offset >= 0)
            val expected = TextIndex(source).positionOf(offset)
            val declarations = tokens.filter {
                it.tokenType == GroovySemanticTokenProvider.TokenTypes.PARAMETER &&
                    it.tokenModifiers and GroovySemanticTokenProvider.TokenModifiers.DECLARATION != 0
            }

            assertEquals(
                listOf(Triple(expected.line, expected.character, name.length)),
                declarations.map { Triple(it.line, it.startChar, it.length) },
            )
            assertEquals(
                name,
                source.lines()[expected.line].substring(
                    expected.character,
                    expected.character + name.length,
                ),
            )
            type?.let { (spelling, kind) ->
                val typeOffset = source.indexOf(declaration) + declaration.indexOf(spelling)
                val typePosition = TextIndex(source).positionOf(typeOffset)
                assertEquals(
                    listOf(Triple(spelling.length, kind, 0)),
                    tokens.filter {
                        it.line == typePosition.line && it.startChar == typePosition.character
                    }.map { Triple(it.length, it.tokenType, it.tokenModifiers) },
                    "The source type must retain its exact spelling, span, and classification",
                )
            }
            tokens.filter {
                it.line == expected.line
            }.sortedBy { it.startChar }.zipWithNext().forEach { (left, right) ->
                assertTrue(left.startChar + left.length <= right.startChar, "Overlapping tokens: $left and $right")
            }
        } finally {
            service.clearCaches()
        }
    }
}

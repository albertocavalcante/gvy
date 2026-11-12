// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpockTableActionTest {
    private val uri = URI.create("file:///ExampleSpec.groovy")

    @Test
    fun `aligns selected table while preserving literal pipes and double separators`() {
        val content =
            spec(
                """
            value | expected || result
            'a|b'|'long value'||true
            'c' | 'd' || false
                """.trimIndent(),
            )
        val actions = actions(content)
        assertEquals(1, actions.size)
        assertEquals(CodeActionKind.RefactorRewrite, actions.single().kind)
        assertEquals(uri.toString(), actions.single().edit.documentChanges.single().left.textDocument.uri)
        assertEquals(7, actions.single().edit.documentChanges.single().left.textDocument.version)
        val edits = actions.single().edit.documentChanges.single().left.edits
        assertEquals(
            listOf("value | expected     || result", "'a|b' | 'long value' || true", "'c'   | 'd'          || false"),
            edits.map { it.newText },
        )
        assertTrue(edits.all { it.range.start.line == it.range.end.line })
        val formattedLines = content.lines().toMutableList()
        edits.forEach { edit ->
            val range = edit.range
            formattedLines[range.start.line] = formattedLines[range.start.line]
                .replaceRange(range.start.character, range.end.character, edit.newText)
        }
        assertTrue(actions(formattedLines.joinToString("\n")).isEmpty(), "Alignment should be idempotent")
    }

    @Test
    fun `aligns a selected table after combined label`() {
        val content = spec("a | b\n100|2\ncombined:\ncLong | d\n3|400")
        val actions = actions(content, Range(Position(8, 8), Position(8, 8)))

        assertEquals(1, actions.size)
        assertEquals(
            listOf("cLong | d", "3     | 400"),
            actions.single().edit.documentChanges.single().left.edits.map { it.newText },
        )
    }

    @Test
    fun `declines ragged tables comments and already aligned tables`() {
        listOf(
            "a | b\n1 | 2 | 3",
            "a | b\n1 | 2 // keep comment",
            "a | b\n100|2\n3|4 // preserve the whole table",
            "a | b\n1 | 2",
        ).forEach { table -> assertTrue(actions(spec(table)).isEmpty(), table) }
    }

    @Test
    fun `declines ordinary Groovy labels and unrelated selections or action kinds`() {
        val content = spec("a | b\n100|2")
        assertTrue(actions(content.replace("extends spock.lang.Specification", "")).isEmpty())
        assertTrue(actions(content, Range(Position(0, 0), Position(0, 1))).isEmpty())
        assertTrue(actions(content, only = listOf(CodeActionKind.QuickFix)).isEmpty())
    }

    @Test
    fun `preserves CRLF line endings with edits confined to table rows`() {
        val content = spec("a | b\n100|2").replace("\n", "\r\n")
        val actions = actions(content)
        assertEquals(1, actions.size)
        assertTrue(
            actions.single().edit.documentChanges.single().left.edits.all {
                '\r' !in it.newText &&
                    '\n' !in it.newText
            },
        )
    }

    private fun spec(table: String) = "class ExampleSpec extends spock.lang.Specification {\n" +
        "    def \"feature\"() {\n" +
        "        expect:\n        true\n        where:\n" +
        table.prependIndent("        ") + "\n    }\n}"

    private fun actions(
        content: String,
        range: Range = Range(Position(5, 8), Position(5, 8)),
        only: List<String>? = null,
    ) = SpockTableAction.createActions(
        SpockTableRequest(
            uri.toString(),
            content,
            GroovyParserFacade().parse(ParseRequest(uri, content, compilePhase = Phases.CONVERSION)).ast,
            range,
            only,
            version = 7,
        ),
    )
}

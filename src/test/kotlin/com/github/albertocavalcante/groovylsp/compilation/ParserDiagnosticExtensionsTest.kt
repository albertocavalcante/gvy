package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovyparser.api.ParserDiagnostic
import com.github.albertocavalcante.groovyparser.api.ParserPosition
import com.github.albertocavalcante.groovyparser.api.ParserRange
import com.github.albertocavalcante.groovyparser.api.ParserSeverity
import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ParserDiagnosticExtensionsTest {

    @Test
    fun `converts parser diagnostic to lsp diagnostic`() {
        val parserDiagnostic = ParserDiagnostic(
            range = ParserRange(ParserPosition(1, 2), ParserPosition(3, 4)),
            severity = ParserSeverity.WARNING,
            message = "something happened",
            source = "parser",
            code = "warn-1",
        )

        val lspDiagnostic = parserDiagnostic.toLspDiagnostic()

        assertEquals(DiagnosticSeverity.Warning, lspDiagnostic.severity)
        assertEquals("something happened", lspDiagnostic.message)
        assertEquals("parser", lspDiagnostic.source)
        assertEquals("warn-1", lspDiagnostic.code?.left)
        assertEquals(1, lspDiagnostic.range.start.line)
        assertEquals(2, lspDiagnostic.range.start.character)
        assertEquals(3, lspDiagnostic.range.end.line)
        assertEquals(4, lspDiagnostic.range.end.character)
    }
}

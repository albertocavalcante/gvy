package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.api.ParserSeverity
import com.github.albertocavalcante.groovyparser.internal.ParserDiagnosticConverter
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.Message
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParserDiagnosticConverterTest {

    private val configuration = CompilerConfiguration()

    @Test
    fun `converts syntax errors to parser diagnostics`() {
        val collector = ErrorCollector(configuration)
        val classLoader = GroovyClassLoader()
        val sourceUnit = SourceUnit(
            "Test.groovy",
            "class Test {}",
            configuration,
            classLoader,
            ErrorCollector(configuration),
        )
        collector.addError(
            SyntaxErrorMessage(
                SyntaxException("boom", 2, 4),
                sourceUnit,
            ),
        )

        val diagnostics = ParserDiagnosticConverter.convert(collector, emptySet())

        assertEquals(1, diagnostics.size)
        val diagnostic = diagnostics.first()
        assertEquals(ParserSeverity.ERROR, diagnostic.severity)
        assertEquals(1, diagnostic.range.start.line)
        assertEquals(3, diagnostic.range.start.character)
        assertTrue(diagnostic.message.contains("boom"))
    }

    @Test
    fun `converts generic messages`() {
        val collector = ErrorCollector(configuration)
        val classLoader = GroovyClassLoader()
        val ignoredSource = SourceUnit("Ignored.groovy", "", configuration, classLoader, ErrorCollector(configuration))
        val includedSource = SourceUnit("Good.groovy", "", configuration, classLoader, ErrorCollector(configuration))
        collector.addError(Message.create("ignored", ignoredSource))
        collector.addError(Message.create("included", includedSource))

        val diagnostics = ParserDiagnosticConverter.convert(collector, emptySet())

        assertEquals(2, diagnostics.size)
    }
}

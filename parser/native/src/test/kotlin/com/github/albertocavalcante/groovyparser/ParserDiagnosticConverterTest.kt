// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.internal.ParserDiagnosticConverter
import com.github.albertocavalcante.nativeapi.ParserSeverity
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.ExceptionMessage
import org.codehaus.groovy.control.messages.LocatedMessage
import org.codehaus.groovy.control.messages.Message
import org.codehaus.groovy.control.messages.SimpleMessage
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.control.messages.WarningMessage
import org.codehaus.groovy.syntax.SyntaxException
import org.codehaus.groovy.syntax.Token
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

    private fun source(name: String = "Test.groovy") =
        SourceUnit(name, "", configuration, GroovyClassLoader(), ErrorCollector(configuration))

    private fun convertOne(message: Message) = ParserDiagnosticConverter
        .convert(ErrorCollector(configuration).also { it.addError(message) }, emptySet())
        .single()

    @Test
    fun `a simple message keeps its text instead of an identity hash`() {
        val diagnostic = convertOne(
            SimpleMessage("Could not find class for Transformation Processor probe.MarkTransform", source()),
        )

        assertTrue(diagnostic.message.contains("probe.MarkTransform"), diagnostic.message)
        assertTrue(!diagnostic.message.contains("@"), "identity hash leaked: ${diagnostic.message}")
    }

    @Test
    fun `an exception message shows the cause`() {
        val diagnostic = convertOne(
            ExceptionMessage(IllegalStateException("transform blew up"), true, source()),
        )

        assertTrue(diagnostic.message.contains("IllegalStateException"), diagnostic.message)
        assertTrue(diagnostic.message.contains("transform blew up"), diagnostic.message)
    }

    @Test
    fun `a located message keeps its text and its position`() {
        val token = Token.newString("x", 7, 5)
        val diagnostic = convertOne(LocatedMessage("bad thing here", token, source()))

        assertTrue(diagnostic.message.contains("bad thing here"), diagnostic.message)
        assertEquals(6, diagnostic.range.start.line)
        assertEquals(4, diagnostic.range.start.character)
        assertEquals(6, diagnostic.range.end.line)
        assertEquals(5, diagnostic.range.end.character)
    }

    @Test
    fun `a warning is converted with its text`() {
        val collector = ErrorCollector(configuration)
        collector.addWarning(WarningMessage(WarningMessage.LIKELY_ERRORS, "careful now", null, source()))

        val diagnostic = ParserDiagnosticConverter.convert(collector, emptySet()).single()

        assertEquals(ParserSeverity.WARNING, diagnostic.severity)
        assertTrue(diagnostic.message.contains("careful now"), diagnostic.message)
    }

    @Test
    fun `an unknown message type falls back to its own written form`() {
        val custom = object : Message() {
            override fun write(writer: java.io.PrintWriter, owner: org.codehaus.groovy.control.Janitor) {
                writer.print("custom compiler message")
            }
        }

        assertEquals("custom compiler message", convertOne(custom).message)
    }

    @Test
    fun `an exception message is cut to its first line and a sane length`() {
        val cause = IllegalStateException("first line /Users/someone/secret/path\nsecond line\n" + "x".repeat(1000))
        val diagnostic = convertOne(ExceptionMessage(cause, true, source()))

        assertTrue(diagnostic.message.startsWith("IllegalStateException: first line"), diagnostic.message)
        assertTrue(!diagnostic.message.contains("second line"), diagnostic.message)
        assertTrue(diagnostic.message.length <= 300, "length ${diagnostic.message.length}")
        val long = convertOne(ExceptionMessage(IllegalStateException("y".repeat(1000)), true, source()))
        assertEquals(300, long.message.length)
    }

    @Test
    fun `the written fallback is also one capped line`() {
        val custom = object : Message() {
            override fun write(writer: java.io.PrintWriter, owner: org.codehaus.groovy.control.Janitor) {
                writer.println("z".repeat(1000))
                writer.println("at some.Frame(Frame.java:1)")
            }
        }

        val message = convertOne(custom).message

        assertEquals(300, message.length)
        assertTrue(!message.contains("Frame"), message)
    }

    private val missingTransform = "Could not find class for Transformation Processor a.B declared by c.D"

    private fun withLocators(name: String, locators: Set<String>, isProject: Boolean) = ParserDiagnosticConverter
        .convert(
            ErrorCollector(configuration).also { it.addError(SimpleMessage(missingTransform, source(name))) },
            locators,
        ) { isProject }

    @Test
    fun `a project transform notice is kept when its source is one of the requested ones`() {
        val diagnostics = withLocators("Good.groovy", setOf("Good.groovy"), isProject = true)

        val notice = diagnostics.single()
        assertEquals(ParserSeverity.INFORMATION, notice.severity)
        assertTrue("later checks for this file were skipped" in notice.message, notice.message)
    }

    @Test
    fun `a project transform message from another source is still dropped`() {
        assertTrue(withLocators("Other.groovy", setOf("Good.groovy"), isProject = true).isEmpty())
    }

    @Test
    fun `a missing transform that is not on the project classpath is not turned into a notice`() {
        assertTrue(withLocators("Good.groovy", setOf("Good.groovy"), isProject = false).isEmpty())
        val unscoped = withLocators("Good.groovy", emptySet(), isProject = false).single()
        assertEquals(ParserSeverity.ERROR, unscoped.severity)
    }
}

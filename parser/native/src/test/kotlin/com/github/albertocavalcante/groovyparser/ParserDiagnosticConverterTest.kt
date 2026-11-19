// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.internal.DiagnosticDocument
import com.github.albertocavalcante.groovyparser.internal.ParserDiagnosticConverter
import com.github.albertocavalcante.groovyparser.internal.SourceSnapshot
import com.github.albertocavalcante.groovyparser.internal.SyntaxSourceContext
import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
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
    fun `syntax errors from another source cannot use the current snapshot`() {
        val exception = SyntaxException("other error", 1, 7).apply { sourceLocator = "Other.groovy" }
        val collector = ErrorCollector(configuration).also {
            it.addError(SyntaxErrorMessage(exception, source("Other.groovy")))
        }
        val content = "import"
        val diagnostics = ParserDiagnosticConverter.convert(
            collector,
            setOf("Test.groovy"),
            SyntaxSourceContext(SourceSnapshot(content), GroovyTokenIndex.build(content), setOf("Test.groovy")),
        )
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `invalid compiler positions cannot be clamped into an incomplete import`() {
        val content = "import"
        val exception = SyntaxException("other error", 1, 100).apply { sourceLocator = "Test.groovy" }
        val collector = ErrorCollector(configuration).also {
            it.addError(SyntaxErrorMessage(exception, source()))
        }
        val diagnostic = ParserDiagnosticConverter.convert(
            collector,
            setOf("Test.groovy"),
            SyntaxSourceContext(SourceSnapshot(content), GroovyTokenIndex.build(content), setOf("Test.groovy")),
        ).single()
        assertEquals("syntax-error", diagnostic.code)
        assertEquals("other error", diagnostic.message)
        assertEquals(content.length, diagnostic.range.start.character)
    }

    @Test
    fun `unscoped syntax errors cannot use a supplied source snapshot`() {
        val exception = SyntaxException("other error", 1, 7).apply { sourceLocator = "Other.groovy" }
        val collector = ErrorCollector(configuration).also {
            it.addError(SyntaxErrorMessage(exception, source("Other.groovy")))
        }
        val content = "import"
        val diagnostic = ParserDiagnosticConverter.convert(
            collector,
            emptySet(),
            SyntaxSourceContext(SourceSnapshot(content), GroovyTokenIndex.build(content), setOf("Test.groovy")),
        ).single()
        assertEquals("syntax-error", diagnostic.code)
        assertEquals("other error", diagnostic.message)
    }

    @Test
    fun `fallback retains a valid compiler span`() {
        val content = "abcdef"
        val exception = SyntaxException("other error", 1, 2, 1, 6).apply { sourceLocator = "Test.groovy" }
        val collector = ErrorCollector(configuration).also {
            it.addError(SyntaxErrorMessage(exception, source()))
        }
        val diagnostic = ParserDiagnosticConverter.convert(
            collector,
            setOf("Test.groovy"),
            SyntaxSourceContext(SourceSnapshot(content), GroovyTokenIndex.build(content), setOf("Test.groovy")),
        ).single()
        assertEquals(1, diagnostic.range.start.character)
        assertEquals(5, diagnostic.range.end.character)
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
    fun `fallback skips compiler boilerplate before a useful raw exception`() {
        val messages = listOf(
            SimpleMessage("startup failed:", source()),
            SimpleMessage("Compilation failed: startup failed:", source()),
            ExceptionMessage(IllegalStateException("Unable to load class: org.slf4j.Logger"), true, source()),
        )
        assertEquals("Unable to load class: org.slf4j.Logger", firstCompilerCause(messages))
    }

    @Test
    fun `fallback skips multiline compiler wrapper and keeps its useful detail`() {
        val messages = listOf(
            SimpleMessage("startup failed:\nUnable to load class: org.slf4j.Logger", source()),
        )
        assertEquals("Unable to load class: org.slf4j.Logger", firstCompilerCause(messages))
    }

    @Test
    fun `fallback skips blank syntax and never renders unknown messages`() {
        val unknown = object : Message() {
            override fun write(writer: java.io.PrintWriter, owner: org.codehaus.groovy.control.Janitor): Unit =
                throw java.util.concurrent.CancellationException("must not be swallowed")
        }
        val messages = listOf(
            SyntaxErrorMessage(SyntaxException("", 1, 1), source("Good.groovy")),
            unknown,
            SimpleMessage("Unable to load class: org.slf4j.Logger", source("Good.groovy")),
        )
        assertEquals("Unable to load class: org.slf4j.Logger", firstCompilerCause(messages))
    }

    @Test
    fun `unlocated compiler exceptions are not assigned to requested tokens`() {
        val collector = ErrorCollector(configuration)
        collector.addError(
            ExceptionMessage(IllegalStateException("own unlocated failure"), true, source("Good.groovy")),
        )
        collector.addError(ExceptionMessage(IllegalStateException("other failure"), true, source("Other.groovy")))
        collector.addError(SimpleMessage("other unlocated failure", source("Other.groovy")))

        val diagnostics = ParserDiagnosticConverter.convert(collector, setOf("Good.groovy"))

        assertTrue(diagnostics.isEmpty(), diagnostics.toString())
    }

    @Test
    fun `scoped diagnostics retain simple and exception errors only for their owner`() {
        val collector = ErrorCollector(configuration)
        collector.addError(SimpleMessage("own simple error", source("Good.groovy")))
        collector.addError(ExceptionMessage(IllegalStateException("own exception"), true, source("Good.groovy")))
        collector.addError(SimpleMessage("other simple error", source("Other.groovy")))
        collector.addError(ExceptionMessage(IllegalStateException("other exception"), true, source("Other.groovy")))

        val diagnostics = ParserDiagnosticConverter.convert(
            collector,
            setOf("Good.groovy"),
            document = DiagnosticDocument("class Good {}"),
        )

        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics.any { it.message.contains("own simple error") })
        assertTrue(diagnostics.any { it.message.contains("IllegalStateException: own exception") })
    }

    @Test
    fun `scoped unlocated errors without document content remain filtered`() {
        val collector = ErrorCollector(configuration)
        collector.addError(ExceptionMessage(IllegalStateException("own exception"), true, source("Good.groovy")))

        assertTrue(ParserDiagnosticConverter.convert(collector, setOf("Good.groovy")).isEmpty())
    }

    @Test
    fun `unlocated owned errors span the requested document in UTF-16 positions`() {
        val collector = ErrorCollector(configuration)
        collector.addError(ExceptionMessage(IllegalStateException("own exception"), true, source("Good.groovy")))

        val diagnostic = ParserDiagnosticConverter.convert(
            collector,
            setOf("Good.groovy"),
            document = DiagnosticDocument("first\r\n😀x"),
        ).single()

        assertEquals(0, diagnostic.range.start.line)
        assertEquals(0, diagnostic.range.start.character)
        assertEquals(1, diagnostic.range.end.line)
        assertEquals(3, diagnostic.range.end.character)
    }

    @Test
    fun `unscoped conversion preserves warnings and uses content only for its owner`() {
        val collector = ErrorCollector(configuration)
        collector.addError(ExceptionMessage(IllegalStateException("own exception"), true, source("Good.groovy")))
        collector.addWarning(WarningMessage(WarningMessage.LIKELY_ERRORS, "warning", null, source("Good.groovy")))

        val diagnostics = ParserDiagnosticConverter.convert(
            collector,
            emptySet(),
            document = DiagnosticDocument("line one\nline two", "Good.groovy"),
        )

        assertEquals(1, diagnostics.single { it.severity == ParserSeverity.ERROR }.range.end.line)
        assertEquals(1, diagnostics.count { it.severity == ParserSeverity.WARNING })
    }

    @Test
    fun `document content is never assigned to a different or unknown owner`() {
        val collector = ErrorCollector(configuration)
        collector.addError(SimpleMessage("other", source("Other.groovy")))
        collector.addError(SimpleMessage("unknown", null))

        val diagnostics = ParserDiagnosticConverter.convert(
            collector,
            setOf("Good.groovy", "Other.groovy"),
            document = DiagnosticDocument("Good source", "Good.groovy"),
        )

        assertTrue(diagnostics.isEmpty(), diagnostics.toString())
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
            document = DiagnosticDocument("class Good {}"),
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
        assertEquals(ParserSeverity.ERROR, withLocators("Good.groovy", setOf("Good.groovy"), false).single().severity)
        val unscoped = withLocators("Good.groovy", emptySet(), isProject = false).single()
        assertEquals(ParserSeverity.ERROR, unscoped.severity)
    }
}

// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.slf4j.LoggerFactory
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParserLoggingVolumeTest {
    @Test
    fun `disabled inventory does not inspect class names or supertypes`() {
        val logger = LoggerFactory.getLogger(GroovyParserFacade::class.java) as Logger
        val previous = logger.level
        try {
            logger.level = Level.INFO
            val parser = GroovyParserFacade()
            val request =
                ParseRequest(
                    URI.create("file:///Probe.groovy"),
                    "class Probe {}",
                    compilePhase = Phases.CANONICALIZATION,
                )
            val initial = parser.parse(request)
            val inspected = ObservedClass()
            val module = ModuleNode(null as SourceUnit?).apply {
                addClass(ClassNode("First", 1, ClassHelper.OBJECT_TYPE))
                addClass(inspected)
            }
            val method = GroovyParserFacade::class.java.getDeclaredMethod(
                "shouldRetryAtConversion",
                ParseRequest::class.java,
                ParseResult::class.java,
            ).apply { isAccessible = true }
            for (level in listOf(Level.INFO, Level.DEBUG)) {
                inspected.reads = 0
                logger.level = level
                assertEquals(false, method.invoke(parser, request, initial.copy(ast = module)))
                assertEquals(0, inspected.reads, "inventory should be lazy at $level")
            }
        } finally {
            logger.level = previous
        }
    }

    private class ObservedClass : ClassNode("Second", 1, ClassHelper.OBJECT_TYPE) {
        var reads = 0
        override fun getName(): String {
            reads++
            return super.getName()
        }
        override fun getSuperClass(): ClassNode = throw NoClassDefFoundError("unloadable ancestor")
    }

    @TestFactory
    fun `multiple classes need no superclass read for script retry`(): List<DynamicTest> =
        listOf(Level.INFO, Level.DEBUG, Level.TRACE).map { level ->
            DynamicTest.dynamicTest(level.toString()) {
                val logger = LoggerFactory.getLogger(GroovyParserFacade::class.java) as Logger
                val previous = logger.level
                try {
                    logger.level = level
                    val parser = GroovyParserFacade()
                    val request = ParseRequest(
                        URI.create("file:///Probe.groovy"),
                        "class Probe {}",
                        compilePhase = Phases.CANONICALIZATION,
                    )
                    val initial = parser.parse(request)
                    val first = UnloadableFirstClass()
                    val module = ModuleNode(null as SourceUnit?).apply {
                        addClass(first)
                        addClass(ClassNode("Second", 1, ClassHelper.OBJECT_TYPE))
                    }
                    val method = GroovyParserFacade::class.java.getDeclaredMethod(
                        "shouldRetryAtConversion",
                        ParseRequest::class.java,
                        ParseResult::class.java,
                    ).apply { isAccessible = true }

                    assertEquals(false, method.invoke(parser, request, initial.copy(ast = module)))
                    assertEquals(0, first.superclassReads, "multi-class retry must not read a superclass at $level")
                } finally {
                    logger.level = previous
                }
            }
        }

    private class UnloadableFirstClass : ClassNode("First", 1, ClassHelper.OBJECT_TYPE) {
        var superclassReads = 0

        override fun getSuperClass(): ClassNode {
            superclassReads++
            throw NoClassDefFoundError("unloadable superclass")
        }
    }

    @Test
    fun `repeated analysis is quiet at info and bounded at debug and trace`() {
        val logger = LoggerFactory.getLogger(GroovyParserFacade::class.java) as Logger
        val previous = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val names = (0 until 60).map { index ->
                if (index == 2) "C02_" + "x".repeat(100) else "C" + index.toString().padStart(2, '0')
            }
            val source = names.joinToString("\n") { "class $it {}" }
            val request =
                ParseRequest(URI.create("file:///Many.groovy"), source, compilePhase = Phases.CANONICALIZATION)
            val results = mutableListOf<List<String>>()
            for (level in listOf(Level.INFO, Level.DEBUG, Level.TRACE)) {
                logger.level = level
                repeat(2) {
                    val result = GroovyParserFacade().parse(request)
                    results += checkNotNull(result.ast).classes.map { it.name }
                    assertTrue(result.diagnostics.isEmpty())
                }
                val messages = appender.list.map { it.formattedMessage }
                when (level) {
                    Level.INFO -> assertTrue(messages.none { it.contains("shouldRetryAtConversion") })
                    Level.DEBUG -> {
                        assertTrue(messages.any { it.contains("shouldRetryAtConversion") })
                        assertTrue(messages.none { it.contains("class inventory") })
                    }
                    Level.TRACE -> {
                        val inventory = messages.filter { it.contains("class inventory") }
                        assertEquals(2, inventory.size)
                        val expectedNames = names.take(16).map { it.take(80) }
                        val expected = "shouldRetryAtConversion: class inventory: " +
                            "[${expectedNames.joinToString()}] (44 omitted)"
                        assertTrue(inventory.all { it == expected })
                        assertTrue(inventory.none { it.contains(names[2]) || it.contains(names[16]) })
                    }
                }
                appender.list.clear()
            }
            assertTrue(results.all { it == results.first() })
        } finally {
            logger.level = previous
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}

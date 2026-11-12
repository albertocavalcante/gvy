// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.ast

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.github.albertocavalcante.groovyparser.ast.visitor.RecursiveAstVisitor
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class LoggingObservationTest {
    private val uri = URI.create("file:///Probe.groovy")

    @Test
    fun `logging does not resolve an unloadable ancestor during traversal and enumeration`() {
        for (level in listOf(Level.INFO, Level.DEBUG, Level.TRACE)) {
            withLevel(level) {
                val ancestor = UnloadableAncestor()
                val child = ClassNode("Probe", 1, ancestor).apply {
                    lineNumber = 1
                    columnNumber = 1
                }
                val module = ModuleNode(null as SourceUnit?).apply { addClass(child) }
                val visitor = RecursiveAstVisitor(NodeRelationshipTracker())

                visitor.visitModule(module, uri)

                assertSame(child, visitor.getAllClassNodes().single())
                assertEquals(listOf(child), visitor.getNodes(uri))
                assertEquals(0, ancestor.hierarchyReads, "logging must not resolve the ancestor at $level")
            }
        }
    }

    @Test
    fun `class enumeration does not resolve an unloadable ancestor for logging`() {
        for (level in listOf(Level.INFO, Level.DEBUG, Level.TRACE)) {
            withLevel(level) {
                val ancestor = UnloadableAncestor()
                val child = ClassNode("Probe", 1, ancestor)
                val module = ModuleNode(null as SourceUnit?).apply { addClass(child) }
                val tracker = NodeRelationshipTracker().apply { setModuleNode(uri, module) }

                assertSame(child, tracker.getAllClassNodes().single())
                assertEquals(0, ancestor.hierarchyReads, "logging must not resolve the ancestor at $level")
            }
        }
    }

    @Test
    fun `cyclic class hierarchy is traversed consistently at every log level`() {
        for (level in listOf(Level.INFO, Level.DEBUG, Level.TRACE)) {
            withLevel(level) {
                val first = ClassNode("First", 1, ClassHelper.OBJECT_TYPE)
                val second = ClassNode("Second", 1, first)
                first.superClass = second
                val module = ModuleNode(null as SourceUnit?).apply {
                    addClass(first)
                    addClass(second)
                }
                val visitor = RecursiveAstVisitor(NodeRelationshipTracker())

                visitor.visitModule(module, uri)

                assertEquals(setOf(first, second), visitor.getAllClassNodes().toSet())
            }
        }
    }

    @Test
    fun `semantic superclass failure still propagates at every log level`() {
        for (level in listOf(Level.INFO, Level.DEBUG, Level.TRACE)) {
            withLevel(level) {
                val broken = UnloadableAncestor()
                val module = ModuleNode(null as SourceUnit?).apply { addClass(broken) }
                val visitor = RecursiveAstVisitor(NodeRelationshipTracker())

                assertFailsWith<NoClassDefFoundError> { visitor.visitModule(module, uri) }
            }
        }
    }

    private fun withLevel(level: Level, block: () -> Unit) {
        val logger = LoggerFactory.getLogger("com.github.albertocavalcante.groovyparser") as Logger
        val previous = logger.level
        try {
            logger.level = level
            block()
        } finally {
            logger.level = previous
        }
    }

    private class UnloadableAncestor : ClassNode("lib.Base", 1, ClassHelper.OBJECT_TYPE) {
        var hierarchyReads = 0

        override fun getSuperClass(): ClassNode {
            hierarchyReads++
            throw NoClassDefFoundError("groovy/lang/GroovyObject")
        }
    }
}

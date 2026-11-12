// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.spock

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.net.URI
import java.time.Duration
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spock detection must return on a class hierarchy cycle (issue #1369). */
class SpockInheritanceCycleTest {
    private fun cyclicNodes(): List<ClassNode> {
        val a = ClassNode("A", 1, ClassNode("java.lang.Object", 1, null))
        val b = ClassNode("B", 1, a)
        a.superClass = b
        return listOf(a, b)
    }

    @Test
    fun `detector and classifier return for a hand-built superclass cycle`() {
        assertTimeoutPreemptively(TIMEOUT) {
            cyclicNodes().forEach { node ->
                assertFalse(SpockDetector.isSpockSpec(node))
                val module = ModuleNode(null as SourceUnit?).also { it.addClass(node) }
                SpockSourceContext.from(module, isPartial = false)
            }
        }
    }

    @Test
    fun `detection returns for a parsed cycle`() {
        assertTimeoutPreemptively(TIMEOUT) {
            val result = GroovyParserFacade().parse(
                ParseRequest(
                    uri = URI.create("file:///CycleSpec.groovy"),
                    content = "class A extends B {}\nclass B extends A {}",
                    compilePhase = Phases.SEMANTIC_ANALYSIS,
                ),
            )
            val context = SpockSourceContext.from(result)
            val available = context as SpockSourceContext.Available
            assertTrue(available.classes.none { it.status == SpecificationStatus.SPECIFICATION })
            result.ast?.classes?.forEach { SpockDetector.isSpockSpec(it, result.ast) }
        }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}

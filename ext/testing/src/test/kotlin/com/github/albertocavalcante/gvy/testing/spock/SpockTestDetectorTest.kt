// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.testing.spock

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.spock.SpockSourceContext
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpockTestDetectorTest {
    private var builds = 0
    private val detector = SpockTestDetector { module ->
        builds++
        SpockSourceContext.from(module, isPartial = false).classes
    }

    private fun parse(source: String): ModuleNode =
        checkNotNull(GroovyParserFacade().parse(ParseRequest(URI.create("file:///src/Probe.groovy"), source)).ast)

    private fun ModuleNode.named(name: String) = classes.single { it.name == name }

    @Test
    fun `two classes in one module build the source context once`() {
        val module = parse(
            """
            import spock.lang.*
            class Specification {}
            class Rule extends Specification {}
            class RealSpec extends spock.lang.Specification {}
            """.trimIndent(),
        )

        assertFalse(detector.appliesTo(module.named("Rule"), module))
        assertTrue(detector.appliesTo(module.named("RealSpec"), module))
        assertFalse(detector.appliesTo(module.named("Specification"), module))
        assertEquals(1, builds)
    }

    @Test
    fun `a different module rebuilds the source context`() {
        val first = parse("class FooSpec extends spock.lang.Specification {}")
        val second = parse("class FooSpec extends spock.lang.Specification {}")

        assertTrue(detector.appliesTo(first.named("FooSpec"), first))
        assertTrue(detector.appliesTo(second.named("FooSpec"), second))
        assertTrue(detector.appliesTo(first.named("FooSpec"), first))
        assertEquals(3, builds)
    }

    @Test
    fun `uncertain classes keep the previous detector`() {
        val module = parse("import spock.lang.*\nclass FooSpec extends Specification {}")

        assertTrue(detector.appliesTo(module.named("FooSpec"), module))
    }
}

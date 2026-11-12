// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.spock

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpockClassContextTest {
    private fun parse(source: String): Pair<SpockSourceContext.Available, ModuleNode> {
        val result = GroovyParserFacade().parse(ParseRequest(URI.create("file:///src/Probe.groovy"), source))
        val module = checkNotNull(result.ast)
        return assertIs<SpockSourceContext.Available>(SpockSourceContext.from(result)) to module
    }

    private fun SpockSourceContext.Available.named(name: String) = classes.single { it.classNode.name == name }

    @Test
    fun `confident verdicts decide and uncertain ones keep the previous detector`() {
        val (source, module) = parse(
            """
            import spock.lang.*
            class Specification {}
            class Rule extends Specification {}
            class Real extends spock.lang.Specification {}
            """.trimIndent(),
        )
        val rule = source.named("Rule")
        assertEquals(SpecificationStatus.NOT_SPECIFICATION, rule.status)
        assertTrue(SpockDetector.isSpockSpec(rule.classNode, module), "the detector alone is fooled")
        assertFalse(rule.isSpecification(module))
        assertTrue(source.named("Real").isSpecification(module))

        val (starOnly, starModule) = parse("import spock.lang.*\nclass FooSpec extends Specification {}")
        val foo = starOnly.named("FooSpec")
        assertEquals(SpecificationStatus.UNCERTAIN, foo.status)
        assertEquals(SpockDetector.isSpockSpec(foo.classNode, starModule), foo.isSpecification(starModule))
        assertTrue(foo.isSpecification(starModule))
    }
}

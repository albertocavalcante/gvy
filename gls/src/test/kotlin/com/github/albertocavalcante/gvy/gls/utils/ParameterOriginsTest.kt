package com.github.albertocavalcante.gvy.gls.utils

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ParameterOriginsTest {
    private val uri = URI.create("file:///MemoCases.groovy")

    @Test
    fun `a source closure proves its own distinct implicit parameter identity`() {
        val source = "class Cases { def earlier = { [1].collect { it } }; String call(String it) { it } }"
        val parsed = GroovyParserFacade(javaClass.classLoader).parse(
            ParseRequest(uri = uri, content = source, compilePhase = Phases.CANONICALIZATION),
        )
        val type = assertNotNull(parsed.ast).classes.single { it.name == "Cases" }
        val target = type.getDeclaredMethods("call").single().parameters.single()
        val closures = parsed.astModel.getAllNodes().filterIsInstance<ClosureExpression>()
        val implicit = closures.map { assertNotNull(it.variableScope.getDeclaredVariable("it") as? Parameter) }
        assertTrue(implicit.size >= 2)
        assertTrue(implicit.distinct().size == implicit.size)
        val origins = ParameterOrigins(parsed.astModel, uri)

        implicit.forEach { assertFalse(origins.matches(it, target)) }
    }

    @Test
    fun `an unowned it parameter is not assumed to be an implicit closure binding`(): Unit = fixture().run {
        val unknown = Parameter(ClassHelper.OBJECT_TYPE, "it")
        val target = Parameter(ClassHelper.OBJECT_TYPE, "it").apply {
            setSourcePosition(original.parameters.single())
        }

        assertFailsWith<IllegalStateException> { origins.matches(unknown, target) }
    }

    @Test
    fun `generated parameter ownership is anchored to its unique source method`(): Unit = fixture().run {
        assertSame(original.parameters.single(), origins.source(clone))
        assertTrue(origins.matches(clone, original.parameters.single()))
        val other = original.declaringClass.getDeclaredMethods("other").single().parameters.single()
        assertFalse(origins.matches(clone, other))
    }

    @Test
    fun `missing generated provenance rejects a source less parameter`(): Unit = fixture().run {
        helper.annotations.clear()

        assertFailsWith<IllegalStateException> { origins.source(clone) }
    }

    @Test
    fun `a body outside its source owner cannot establish parameter ownership`(): Unit = fixture().run {
        helper.code.lastLineNumber = original.lastLineNumber + 1

        assertFailsWith<IllegalStateException> { origins.source(clone) }
    }

    @Test
    fun `incompatible complete signatures reject a cloned parameter`(): Unit = fixture().run {
        original.parameters.single().type = ClassHelper.int_TYPE

        assertFailsWith<IllegalStateException> { origins.source(clone) }
    }

    @Test
    fun `ambiguous source owners reject the rename rather than selecting the first`(): Unit = fixture().run {
        val other = original.declaringClass.getDeclaredMethods("other").single()
        other.setSourcePosition(original)

        assertFailsWith<IllegalStateException> { origins.source(clone) }
    }

    @Test
    fun `unowned source less parameters cannot fall back to their name and type`(): Unit = fixture().run {
        val unknown = Parameter(ClassHelper.STRING_TYPE, "value")

        assertFailsWith<IllegalStateException> { origins.matches(unknown, original.parameters.single()) }
    }

    private fun fixture(): Fixture {
        val source = """
            class MemoCases {
                @groovy.transform.Memoized
                String one(String value) { value }
                @groovy.transform.Memoized
                String other(String value) { value }
            }
        """.trimIndent()
        val parsed = GroovyParserFacade(javaClass.classLoader).parse(
            ParseRequest(uri = uri, content = source, compilePhase = Phases.CANONICALIZATION),
        )
        val type = assertNotNull(parsed.ast).classes.single { it.name == "MemoCases" }
        val original = type.getDeclaredMethods("one").single()
        val helper = type.methods.single { method ->
            method.lineNumber < 0 && method.code?.lastLineNumber == original.lastLineNumber &&
                method.parameters.size == 1 && method.parameters.single().name == "value"
        }
        val clone = helper.parameters.single()
        assertTrue(clone !== original.parameters.single(), "A real transform must clone the parameter")
        return Fixture(original, helper, clone, ParameterOrigins(parsed.astModel, uri))
    }

    private data class Fixture(
        val original: MethodNode,
        val helper: MethodNode,
        val clone: Parameter,
        val origins: ParameterOrigins,
    )
}

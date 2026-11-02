package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * References report the identifier, not the whole node, and only for calls that can reach the
 * declaration. Each test lists the exact (line, column) of every expected name.
 */
class ReferenceNameRangeTest {
    private val service = GroovyCompilationService()
    private val uri = URI.create("file:///refs/Sample.groovy")

    private suspend fun references(content: String, line: Int, column: Int, phase: Int? = null): List<Pair<Int, Int>> {
        if (phase == null) service.compile(uri, content) else service.compile(uri, content, compilePhase = phase)
        val locations: List<Location> = ReferenceProvider(service)
            .provideReferences(uri.toString(), Position(line, column), includeDeclaration = true)
            .toList()
        return locations.map { loc ->
            val lines = content.lines()
            val text = lines[loc.range.start.line].substring(loc.range.start.character, loc.range.end.character)
            assertEquals(loc.range.start.line, loc.range.end.line, "a name spans one line: $loc")
            Pair(loc.range.start.line, loc.range.start.character).also { check(text.isNotEmpty()) }
                .also { assertEquals(nameAt(content, it), text, "range covers exactly the name") }
        }.sortedWith(compareBy({ it.first }, { it.second }))
    }

    private fun nameAt(content: String, at: Pair<Int, Int>): String =
        content.lines()[at.first].substring(at.second).takeWhile { Character.isJavaIdentifierPart(it) }

    @Test
    fun `method references are its name and calls that can reach it, not calls on other types`(): Unit = runBlocking {
        val content = """
                class Service {
                    void performAction() {
                        println "Action"
                    }
                    void run() {
                        performAction()
                        this.performAction()
                        "x".performAction()
                    }
                }
        """.trimIndent()

        val refs = references(content, 1, 12)

        assertEquals(listOf(1 to 9, 5 to 8, 6 to 13), refs)
    }

    @Test
    fun `class references are the class name and its uses`(): Unit = runBlocking {
        val content = """
            class Service {
                String name
            }
            def s = new Service()
        """.trimIndent()

        val refs = references(content, 0, 7)

        assertEquals(listOf(0 to 6, 3 to 12), refs)
    }

    @Test
    fun `field references are the field name and its uses`(): Unit = runBlocking {
        val content = """
            class Holder {
                String label
                String show() { return label }
            }
        """.trimIndent()

        val refs = references(content, 1, 12)

        assertEquals(listOf(1 to 11, 2 to 27), refs)
    }

    @Test
    fun `nested class references use the simple name`(): Unit = runBlocking {
        val content = """
            class Outer {
                static class Inner {}
                def make() { new Inner() }
            }
            def i = new Outer.Inner()
        """.trimIndent()

        val refs = references(content, 1, 18)

        assertEquals(listOf(1 to 17, 2 to 21, 4 to 18), refs)
    }

    @Test
    fun `a trait method is reached through a class that implements the trait`(): Unit = runBlocking {
        val content = """
            trait T {
                String greet() { "hi" }
            }
            class C implements T {}
            def r = new C().greet()
        """.trimIndent()

        val refs = references(content, 1, 12)

        assertEquals(listOf(1 to 11, 4 to 16), refs)
    }

    @Test
    fun `typed calls still match when the file only compiled to conversion`(): Unit = runBlocking {
        val content = """
            package p
            import spock.lang.Specification
            class C extends Specification {
                void m() {}
            }
            def r = new C().m()
        """.trimIndent()

        val refs = references(content, 3, 10, phase = org.codehaus.groovy.control.Phases.CONVERSION)

        assertEquals(listOf(3 to 9, 5 to 16), refs)
    }

    @Test
    fun `annotation strings are not mistaken for the field name`(): Unit = runBlocking {
        val content = """
            class Dto {
                @SuppressWarnings("id") Long id
                Long next() { return id }
            }
        """.trimIndent()

        val refs = references(content, 1, 33)

        assertEquals(listOf(1 to 33, 2 to 25), refs)
    }

    @Test
    fun `annotation arguments are not mistaken for the method name`(): Unit = runBlocking {
        val content = """
            class A {
                @Deprecated(since = "foo") void foo() {}
                void run() { foo() }
            }
        """.trimIndent()

        val refs = references(content, 1, 37)

        assertEquals(listOf(1 to 36, 2 to 17), refs)
    }

    @Test
    fun `a spread call reaches the method on each element`(): Unit = runBlocking {
        val content = """
            class C {
                void foo() {}
            }
            def r = [new C()]*.foo()
        """.trimIndent()

        val refs = references(content, 1, 13)

        assertEquals(listOf(1 to 9, 3 to 19), refs)
    }

    @Test
    fun `same-named nested classes in different outers are different classes`(): Unit = runBlocking {
        val content = """
            class X { static class Inner { void m() {} } }
            class Y { static class Inner { void m() {} } }
            def a = new X.Inner().m()
            def b = new Y.Inner().m()
        """.trimIndent()

        val refs = references(content, 0, 37)

        assertEquals(listOf(0 to 36, 2 to 22), refs)
    }

    @Test
    fun `a slashy-string annotation does not hide the declared name`(): Unit = runBlocking {
        val content = """
            class Form {
                @Deprecated(since = /^[^']+$/) String name
                String show() { return name }
            }
        """.trimIndent()

        val refs = references(content, 2, 27)

        assertEquals(listOf(1 to 42, 2 to 27), refs)
    }
}

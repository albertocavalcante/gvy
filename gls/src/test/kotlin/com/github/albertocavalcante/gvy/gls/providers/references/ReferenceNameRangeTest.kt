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

    private suspend fun references(content: String, line: Int, column: Int): List<Pair<Int, Int>> {
        service.compile(uri, content)
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
}

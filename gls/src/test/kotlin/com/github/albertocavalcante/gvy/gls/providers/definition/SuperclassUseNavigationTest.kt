// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.highlight.DocumentHighlightProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * The AST model tracks `extends Base` as the use in the subclass's header (its unresolved superclass), never the
 * resolved declaration, whose positions belong to whichever file declares it (#2438).
 */
class SuperclassUseNavigationTest {
    private val compilation = GroovyCompilationService()

    @AfterEach
    fun tearDown() = compilation.clearCaches()

    private val sameFile = "class Base { }\nclass Child extends Base { }\n"
    private val onExtends = Position(1, 21)

    private fun range(line: Int, start: Int, end: Int) = Range(Position(line, start), Position(line, end))

    @Test
    fun `go to definition on extends Base reaches the class declared in the same file`(): Unit = runBlocking {
        val uri = URI.create("file:///SameFileSuper.groovy")
        assertTrue(compilation.compile(uri, sameFile).isSuccess)

        val definitions = DefinitionProvider(compilation).provideDefinitions(uri.toString(), onExtends).toList()

        assertEquals(listOf(uri.toString() to 0), definitions.map { it.uri to it.range.start.line })
    }

    @Test
    fun `document highlight on extends Base marks the declaration name and the use`(): Unit = runBlocking {
        val uri = URI.create("file:///SameFileHighlight.groovy")
        assertTrue(compilation.compile(uri, sameFile).isSuccess)

        val highlights = DocumentHighlightProvider(compilation).provideHighlights(uri.toString(), onExtends)

        assertEquals(setOf(range(0, 6, 10), range(1, 20, 24)), highlights.map { it.range }.toSet())
    }

    private val baseUri = URI.create("file:///nav/Base.groovy")
    private val childUri = URI.create("file:///nav/Child.groovy")
    private val crossFileChild = "class Child extends Base {\n    Child() { super() }\n}\n"

    private suspend fun compileBoth() {
        assertTrue(compilation.compile(baseUri, "class Base {\n    Base() { }\n}\n").isSuccess)
        compilation.compile(childUri, crossFileChild)
    }

    @Test
    fun `go to definition on extends Base reaches the class declared in another file`(): Unit = runBlocking {
        compileBoth()

        val definitions = DefinitionProvider(compilation)
            .provideDefinitions(childUri.toString(), Position(0, 21)).toList()

        assertEquals(listOf(baseUri.toString() to 0), definitions.map { it.uri to it.range.start.line })
    }

    @Test
    fun `document highlight on extends Base of another file marks only the use in this file`(): Unit = runBlocking {
        compileBoth()

        val highlights = DocumentHighlightProvider(compilation).provideHighlights(childUri.toString(), Position(0, 21))

        assertEquals(listOf(range(0, 20, 24)), highlights.map { it.range })
    }
}

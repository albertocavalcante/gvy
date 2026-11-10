package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionResolver
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cross-file class definitions come from the class-name lookup, for closed and unsaved files (#1312). */
class GlobalClassWorkspaceLookupTest {
    @TempDir
    lateinit var tempDir: Path

    private val compilation = spyk(GroovyCompilationService())
    private val strategy = GlobalClassResolutionStrategy(compilation)

    @AfterEach
    fun close() = compilation.clearCaches()

    @Test
    fun `a class in a closed workspace file resolves to that file`(): Unit = runBlocking {
        val closed = file("Greeter.groovy", "package app\nclass Greeter {}")
        compilation.symbolIndexingService.indexFile(closed)

        assertEquals(closed to "app.Greeter", resolve("Greeter"))
        assertEquals(closed to "app.Greeter", resolve("app.Greeter"))
    }

    @Test
    fun `an unsaved buffer resolves and a renamed class does not`(): Unit = runBlocking {
        val buffer = tempDir.resolve("Draft.groovy").toUri() // never written to disk
        assertTrue(compilation.compile(buffer, "package buf\nclass Draft {}").isSuccess)
        assertEquals(buffer to "buf.Draft", resolve("Draft"))

        assertTrue(compilation.compile(buffer, "package buf\nclass Renamed {}").isSuccess)

        assertEquals(null, resolve("Draft"))
    }

    @Test
    fun `files are tried in URI order as before`(): Unit = runBlocking {
        val second = file("b.groovy", "package b\nclass Shared {}")
        val first = file("a.groovy", "package a\nclass Shared {}")
        listOf(second, first).forEach { compilation.symbolIndexingService.indexFile(it) }

        assertEquals(first to "a.Shared", resolve("Shared"))
    }

    @Test
    fun `the lookup does not scan every indexed file`(): Unit = runBlocking {
        compilation.symbolIndexingService.indexFile(file("Greeter.groovy", "package app\nclass Greeter {}"))

        resolve("Greeter")

        verify(exactly = 0) { compilation.getAllSymbolStorages() }
    }

    private fun file(name: String, content: String): URI = tempDir.resolve(name).also { it.writeText(content) }.toUri()

    private suspend fun resolve(name: String): Pair<URI, String>? {
        val context = ResolutionContext(
            targetNode = ClassNode(name, 0, ClassHelper.OBJECT_TYPE),
            documentUri = URI.create("file:///Main.groovy"),
            position = Position(0, 0),
        )
        return strategy.resolve(context).fold(
            ifLeft = { null },
            ifRight = { result ->
                val source = result as DefinitionResolver.DefinitionResult.Source
                source.uri to (source.node as ClassNode).name
            },
        )
    }
}

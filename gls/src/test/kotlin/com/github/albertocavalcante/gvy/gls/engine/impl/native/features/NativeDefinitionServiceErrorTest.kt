package com.github.albertocavalcante.gvy.gls.engine.impl.native.features

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.engine.adapters.ParseUnit
import com.github.albertocavalcante.gvy.gls.engine.adapters.UnifiedNode
import com.github.albertocavalcante.gvy.gls.engine.adapters.UnifiedSymbol
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigationService
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFailsWith

/** The engine's definition path, like the provider's, never turns a fatal [Error] into "not found" (#1311). */
class NativeDefinitionServiceErrorTest {
    private val uri = URI.create("file:///test/Imports.groovy")
    private val compilation = GroovyCompilationService()

    /** Overflows when a classpath strategy asks for a class's source: a recursion bug inside the strategies. */
    private val overflowing = object : SourceNavigator by SourceNavigationService() {
        override suspend fun navigateToSource(classpathUri: URI, className: String): SourceNavigator.SourceResult =
            throw StackOverflowError("simulated deep recursion")
    }

    private val unit = object : ParseUnit {
        override val uri: String = this@NativeDefinitionServiceErrorTest.uri.toString()
        override val isSuccessful: Boolean = true
        override val diagnostics: List<Diagnostic> = emptyList()
        override fun nodeAt(position: Position): UnifiedNode? = null
        override fun allSymbols(): List<UnifiedSymbol> = emptyList()
    }

    @Test
    fun `a stack overflow inside definition resolution escapes the engine's definition service`() {
        runBlocking { compilation.compile(uri, "import java.util.ArrayList\n\nclass Imports {}\n") }
        val service = NativeDefinitionService(compilation, overflowing)

        assertFailsWith<StackOverflowError> {
            runBlocking { service.findDefinition(null, unit, Position(0, IMPORTED_CLASS_COLUMN)) }
        }
    }

    private companion object {
        /** Inside `ArrayList` in `import java.util.ArrayList`. */
        const val IMPORTED_CLASS_COLUMN = 20
    }
}

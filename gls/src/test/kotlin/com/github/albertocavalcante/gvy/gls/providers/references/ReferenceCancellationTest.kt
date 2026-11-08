package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ReferenceCancellationTest {
    @Test
    fun `reference scanning propagates cancellation instead of completing with no results`(): Unit = runBlocking {
        val uri = URI.create("file:///CancelledReferences.groovy")
        val compilation = GroovyCompilationService()
        compilation.compile(uri, "def value = 1\nprintln value\n")
        val visitor = assertNotNull(compilation.getAstModel(uri))
        mockkObject(visitor)
        try {
            every { visitor.getAllNodes() } throws CancellationException("reference scan cancelled")

            assertFailsWith<CancellationException> {
                ReferenceProvider(compilation)
                    .provideReferences(uri.toString(), Position(0, 4), includeDeclaration = true)
                    .toList()
            }
        } finally {
            unmockkObject(visitor)
            compilation.clearCaches()
        }
    }
}

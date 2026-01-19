package com.github.albertocavalcante.gvy.gls.providers.typedefinition

import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TypeDefinitionParams
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypeDefinitionProviderTest {

    private lateinit var typeDefinitionProvider: TypeDefinitionProvider
    private lateinit var coroutineScope: CoroutineScope
    private lateinit var semanticResolver: SemanticTypeResolver
    private lateinit var sourceNavigator: SourceNavigator

    private val testUri = URI.create("file:///test.groovy")
    private val testPosition = Position(5, 10)

    @BeforeEach
    fun setUp() {
        // Use real CoroutineScope instead of TestScope to avoid hanging
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Mock dependencies
        semanticResolver = mockk(relaxed = true)
        sourceNavigator = mockk(relaxed = true)

        val contextProvider = { _: URI -> null } // Simplified - returns null

        typeDefinitionProvider = TypeDefinitionProvider(
            coroutineScope = coroutineScope,
            semanticResolver = semanticResolver,
            sourceNavigator = sourceNavigator,
            contextProvider = contextProvider,
        )
    }

    @Test
    fun `provideTypeDefinition returns empty list when no compilation context`() = runTest {
        // Given
        val params = TypeDefinitionParams().apply {
            textDocument = TextDocumentIdentifier(testUri.toString())
            position = testPosition
        }

        // When - use timeout to prevent hanging
        val result = typeDefinitionProvider.provideTypeDefinition(params).get(5, TimeUnit.SECONDS)

        // Then
        assertTrue(result.isEmpty())
    }

    @Test
    fun `TypeDefinitionProviderFactory creates provider correctly`() {
        // Given
        val semanticResolver = SemanticTypeResolver(ReflectionTypeSolver())
        val sourceNavigator = mockk<SourceNavigator>()
        val contextProvider = { _: URI -> null }

        // When
        val provider = TypeDefinitionProviderFactory.create(
            coroutineScope = coroutineScope,
            semanticResolver = semanticResolver,
            sourceNavigator = sourceNavigator,
            contextProvider = contextProvider,
        )

        // Then
        assertEquals(TypeDefinitionProvider::class, provider::class)
    }
}

package com.github.albertocavalcante.groovylsp.providers.definition.resolution

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.services.ClasspathService
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.expr.VariableExpression
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MethodResolutionStrategyTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var sourceNavigator: SourceNavigator
    private lateinit var classpathService: ClasspathService
    private lateinit var strategy: MethodResolutionStrategy

    @BeforeEach
    fun setUp() {
        compilationService = mockk()
        sourceNavigator = mockk()
        classpathService = mockk()

        every { compilationService.classpathService } returns classpathService
        every { classpathService.getTypeSolver() } returns mockk()

        strategy = MethodResolutionStrategy(compilationService, sourceNavigator)
    }

    @Test
    fun `strategy is instantiated correctly`() {
        // Simple smoke test to verify the strategy can be instantiated
        assertTrue(strategy is MethodResolutionStrategy)
    }

    @Test
    fun `returns not applicable for non-MethodCallExpression nodes`() = runBlocking {
        val context = mockk<ResolutionContext>()
        val variableExpr = VariableExpression("test")
        every { context.targetNode } returns variableExpr

        val result = strategy.resolve(context)

        // Should return Either.Left (error) for non-applicable nodes
        assertTrue(result.isLeft())
    }
}

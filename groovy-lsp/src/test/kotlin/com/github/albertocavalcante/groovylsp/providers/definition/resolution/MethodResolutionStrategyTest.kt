package com.github.albertocavalcante.groovylsp.providers.definition.resolution

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.services.ClasspathService
import com.github.albertocavalcante.groovylsp.services.ReflectedMethod
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

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

    @Test
    fun `resolves method call to classpath location`() = runBlocking {
        // Setup: Create a mock method call expression
        val methodCall = mockk<MethodCallExpression>()
        val classExpr = mockk<ClassExpression>()
        val classNode = mockk<ClassNode>()
        val args = ArgumentListExpression(listOf(ConstantExpression("test")))

        every { methodCall.methodAsString } returns "format"
        every { methodCall.objectExpression } returns classExpr
        every { methodCall.isImplicitThis } returns false
        every { methodCall.arguments } returns args
        every { classExpr.type } returns classNode
        every { classNode.name } returns "java.lang.String"

        val context = mockk<ResolutionContext>()
        every { context.targetNode } returns methodCall
        every { context.documentUri } returns URI("file:///test.groovy")

        // Mock classpath service to return a method
        val reflectedMethod = ReflectedMethod(
            name = "format",
            parameters = listOf("String"),
            parameterNames = listOf("format"),
            returnType = "String",
            declaringClass = "java.lang.String",
            isStatic = true,
            isPublic = true,
            doc = "Formats string",
        )
        every { classpathService.getMethods("java.lang.String") } returns listOf(reflectedMethod)

        // Mock findClasspathClass to return a classpath URI
        val classpathUri = URI("jar:file:///path/to/rt.jar!/java/lang/String.class")
        every { compilationService.findClasspathClass("java.lang.String") } returns classpathUri

        // Mock source navigator to return source location
        coEvery {
            sourceNavigator.navigateToMethodSource(classpathUri, "java.lang.String", "format")
        } returns SourceNavigator.SourceResult.SourceLocation(
            uri = URI("file:///jdk/src/String.java"),
            className = "java.lang.String",
            lineNumber = 42,
        )

        val result = strategy.resolve(context)

        assertTrue(result.isRight())
        val definition = result.getOrNull()
        assertNotNull(definition)
    }

    @Test
    fun `returns not found when method missing from classpath`() = runBlocking {
        // Setup: Create a mock method call expression
        val methodCall = mockk<MethodCallExpression>()
        val classExpr = mockk<ClassExpression>()
        val classNode = mockk<ClassNode>()

        every { methodCall.methodAsString } returns "unknownMethod"
        every { methodCall.objectExpression } returns classExpr
        every { methodCall.isImplicitThis } returns false
        every { methodCall.arguments } returns ArgumentListExpression(emptyList())
        every { classExpr.type } returns classNode
        every { classNode.name } returns "java.lang.String"

        val context = mockk<ResolutionContext>()
        every { context.targetNode } returns methodCall
        every { context.documentUri } returns URI("file:///test.groovy")

        // Mock empty classpath methods
        every { classpathService.getMethods("java.lang.String") } returns emptyList()

        val result = strategy.resolve(context)

        assertTrue(result.isLeft())
    }

    @Test
    fun `extracts argument count from ArgumentListExpression`() = runBlocking {
        // Setup: Create a method call with 3 arguments
        val methodCall = mockk<MethodCallExpression>()
        val classExpr = mockk<ClassExpression>()
        val classNode = mockk<ClassNode>()
        val args = ArgumentListExpression(
            listOf(
                ConstantExpression("arg1"),
                ConstantExpression("arg2"),
                ConstantExpression("arg3"),
            ),
        )

        every { methodCall.methodAsString } returns "printf"
        every { methodCall.objectExpression } returns classExpr
        every { methodCall.isImplicitThis } returns false
        every { methodCall.arguments } returns args
        every { classExpr.type } returns classNode
        every { classNode.name } returns "java.io.PrintStream"

        val context = mockk<ResolutionContext>()
        every { context.targetNode } returns methodCall
        every { context.documentUri } returns URI("file:///test.groovy")

        // Mock classpath service with overloaded methods
        val method1 = ReflectedMethod(
            name = "printf",
            parameters = listOf("String"),
            parameterNames = listOf("format"),
            returnType = "PrintStream",
            declaringClass = "java.io.PrintStream",
            isStatic = false,
            isPublic = true,
            doc = "Printf with 1 arg",
        )
        val method2 = ReflectedMethod(
            name = "printf",
            parameters = listOf("String", "Object", "Object"),
            parameterNames = listOf("format", "arg1", "arg2"),
            returnType = "PrintStream",
            declaringClass = "java.io.PrintStream",
            isStatic = false,
            isPublic = true,
            doc = "Printf with 3 args",
        )
        every { classpathService.getMethods("java.io.PrintStream") } returns listOf(method1, method2)

        // Mock findClasspathClass
        val classpathUri = URI("jar:file:///path/to/rt.jar!/java/io/PrintStream.class")
        every { compilationService.findClasspathClass("java.io.PrintStream") } returns classpathUri

        // Mock source navigator - method should be selected based on arg count
        coEvery {
            sourceNavigator.navigateToMethodSource(classpathUri, "java.io.PrintStream", "printf")
        } returns SourceNavigator.SourceResult.BinaryOnly(
            uri = classpathUri,
            className = "java.io.PrintStream",
            reason = "Source not available",
        )

        val result = strategy.resolve(context)

        // Should attempt to resolve using the 3-argument overload
        assertTrue(result.isLeft()) // Falls back because source nav returns BinaryOnly
    }

    @Test
    fun `returns not found when receiver type cannot be resolved`() = runBlocking {
        // Setup: Create a method call with implicit this
        val methodCall = mockk<MethodCallExpression>()
        val receiver = VariableExpression("this")

        every { methodCall.methodAsString } returns "someMethod"
        every { methodCall.objectExpression } returns receiver
        every { methodCall.isImplicitThis } returns true
        every { methodCall.arguments } returns ArgumentListExpression(emptyList())

        val context = mockk<ResolutionContext>()
        every { context.targetNode } returns methodCall
        every { context.documentUri } returns URI("file:///test.groovy")

        val result = strategy.resolve(context)

        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertNotNull(error)
        assertTrue(error?.reason?.contains("receiver type") == true)
    }
}

package com.github.albertocavalcante.groovylsp.providers.definition.resolution

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionResolver
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
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Tests for [MethodResolutionStrategy].
 *
 * ## Test Categories
 * - **Applicability**: Tests for when the strategy should/shouldn't apply
 * - **Receiver Resolution**: Tests for resolving the type of the method call receiver
 * - **Method Matching**: Tests for finding the correct overloaded method
 * - **Source Navigation**: Tests for navigating to method source code
 * - **Edge Cases**: QA tests for potential bugs and edge cases
 */
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

    // =============================================================================
    // Applicability Tests
    // =============================================================================

    @Nested
    inner class ApplicabilityTests {

        @Test
        fun `returns not applicable for VariableExpression nodes`() = runBlocking {
            val context = mockk<ResolutionContext>()
            every { context.targetNode } returns VariableExpression("test")

            val result = strategy.resolve(context)

            assertTrue(result.isLeft(), "Should return Left for non-MethodCallExpression")
            val error = result.leftOrNull()
            assertNotNull(error)
            assertEquals("Method", error?.source, "Error source should be 'Method' strategy")
        }

        @Test
        fun `returns not applicable for ConstantExpression nodes`() = runBlocking {
            val context = mockk<ResolutionContext>()
            every { context.targetNode } returns ConstantExpression("literal")

            val result = strategy.resolve(context)

            assertTrue(result.isLeft())
        }

        // FIXME(#843): Dynamic method names (GString interpolation) return null from methodAsString
        // The strategy should handle this gracefully instead of returning notApplicable
        @Test
        @Disabled("Requires methodAsString null handling - see issue #843")
        fun `returns not applicable when methodAsString is null (dynamic method name)`() = runBlocking {
            val methodCall = mockk<MethodCallExpression>()
            every { methodCall.methodAsString } returns null // Dynamic method name like "$methodName"()

            val context = mockk<ResolutionContext>()
            every { context.targetNode } returns methodCall

            val result = strategy.resolve(context)

            assertTrue(result.isLeft())
            val error = result.leftOrNull()
            assertTrue(
                error?.reason?.contains("dynamic") == true || error?.reason?.contains("not applicable") == true,
                "Error should mention dynamic method name or not applicable",
            )
        }
    }

    // =============================================================================
    // Receiver Resolution Tests
    // =============================================================================

    @Nested
    inner class ReceiverResolutionTests {

        @Test
        fun `resolves static method call receiver from ClassExpression`() = runBlocking {
            val methodCall = mockk<MethodCallExpression>()
            val classExpr = mockk<ClassExpression>()
            val classNode = mockk<ClassNode>()

            every { methodCall.methodAsString } returns "valueOf"
            every { methodCall.objectExpression } returns classExpr
            every { methodCall.isImplicitThis } returns false
            every { methodCall.arguments } returns ArgumentListExpression(listOf(ConstantExpression("test")))
            every { classExpr.type } returns classNode
            every { classNode.name } returns "java.lang.Integer"

            val context = mockk<ResolutionContext>()
            every { context.targetNode } returns methodCall
            every { context.documentUri } returns URI("file:///test.groovy")

            val reflectedMethod = createReflectedMethod(
                name = "valueOf",
                parameters = listOf("String"),
                declaringClass = "java.lang.Integer",
                isStatic = true,
            )
            every { classpathService.getMethods("java.lang.Integer") } returns listOf(reflectedMethod)

            val classpathUri = URI("jar:file:///path/to/rt.jar!/java/lang/Integer.class")
            every { compilationService.findClasspathClass("java.lang.Integer") } returns classpathUri

            coEvery {
                sourceNavigator.navigateToMethodSource(classpathUri, "java.lang.Integer", "valueOf")
            } returns SourceNavigator.SourceResult.SourceLocation(
                uri = URI("file:///jdk/src/Integer.java"),
                className = "java.lang.Integer",
                lineNumber = 100,
            )

            val result = strategy.resolve(context)

            assertTrue(result.isRight(), "Should resolve static method call")
        }

        @Test
        fun `returns error when receiver type cannot be resolved for implicit this`() = runBlocking {
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
            assertTrue(
                error?.reason?.contains("receiver type") == true,
                "Error should mention receiver type resolution failure",
            )
        }
    }

    // =============================================================================
    // Method Matching Tests
    // =============================================================================

    @Nested
    inner class MethodMatchingTests {

        @Test
        fun `returns not found when method not in classpath`() = runBlocking {
            val methodCall = mockk<MethodCallExpression>()
            val classExpr = mockk<ClassExpression>()
            val classNode = mockk<ClassNode>()

            every { methodCall.methodAsString } returns "nonExistentMethod"
            every { methodCall.objectExpression } returns classExpr
            every { methodCall.isImplicitThis } returns false
            every { methodCall.arguments } returns ArgumentListExpression(emptyList())
            every { classExpr.type } returns classNode
            every { classNode.name } returns "java.lang.String"

            val context = mockk<ResolutionContext>()
            every { context.targetNode } returns methodCall
            every { context.documentUri } returns URI("file:///test.groovy")

            every { classpathService.getMethods("java.lang.String") } returns emptyList()

            val result = strategy.resolve(context)

            assertTrue(result.isLeft())
            val error = result.leftOrNull()
            assertTrue(
                error?.reason?.contains("not found") == true,
                "Error should indicate method was not found",
            )
        }

        @Test
        fun `matches method by name and argument count`() = runBlocking {
            val methodCall = mockk<MethodCallExpression>()
            val classExpr = mockk<ClassExpression>()
            val classNode = mockk<ClassNode>()
            val args = ArgumentListExpression(
                listOf(ConstantExpression("arg1"), ConstantExpression("arg2")),
            )

            every { methodCall.methodAsString } returns "substring"
            every { methodCall.objectExpression } returns classExpr
            every { methodCall.isImplicitThis } returns false
            every { methodCall.arguments } returns args
            every { classExpr.type } returns classNode
            every { classNode.name } returns "java.lang.String"

            val context = mockk<ResolutionContext>()
            every { context.targetNode } returns methodCall
            every { context.documentUri } returns URI("file:///test.groovy")

            // Two overloads: substring(int) and substring(int, int)
            val method1Arg = createReflectedMethod(
                name = "substring",
                parameters = listOf("int"),
                declaringClass = "java.lang.String",
            )
            val method2Args = createReflectedMethod(
                name = "substring",
                parameters = listOf("int", "int"),
                declaringClass = "java.lang.String",
            )
            every { classpathService.getMethods("java.lang.String") } returns listOf(method1Arg, method2Args)

            val classpathUri = URI("jar:file:///path/to/rt.jar!/java/lang/String.class")
            every { compilationService.findClasspathClass("java.lang.String") } returns classpathUri

            coEvery {
                sourceNavigator.navigateToMethodSource(classpathUri, "java.lang.String", "substring")
            } returns SourceNavigator.SourceResult.SourceLocation(
                uri = URI("file:///jdk/src/String.java"),
                className = "java.lang.String",
                lineNumber = 200,
            )

            val result = strategy.resolve(context)

            // The strategy should find the 2-arg overload based on argument count
            assertTrue(result.isRight(), "Should resolve to method with matching arg count")
        }

        // TODO(#843): Verify that the CORRECT overload is selected when multiple match by name
        // Currently the test only checks that SOME method resolves, not that it's the right one
        @Test
        @Disabled("Need to verify correct overload selection - see issue #843")
        fun `selects correct overload based on argument count`() = runBlocking {
            val methodCall = mockk<MethodCallExpression>()
            val classExpr = mockk<ClassExpression>()
            val classNode = mockk<ClassNode>()
            // 3 arguments
            val args = ArgumentListExpression(
                listOf(
                    ConstantExpression("format"),
                    ConstantExpression("arg1"),
                    ConstantExpression("arg2"),
                ),
            )

            every { methodCall.methodAsString } returns "format"
            every { methodCall.objectExpression } returns classExpr
            every { methodCall.isImplicitThis } returns false
            every { methodCall.arguments } returns args
            every { classExpr.type } returns classNode
            every { classNode.name } returns "java.lang.String"

            val context = mockk<ResolutionContext>()
            every { context.targetNode } returns methodCall
            every { context.documentUri } returns URI("file:///test.groovy")

            // Multiple overloads with different arg counts
            val method1Arg = createReflectedMethod("format", listOf("String"), "java.lang.String")
            val method2Args = createReflectedMethod("format", listOf("String", "Object"), "java.lang.String")
            val method3Args = createReflectedMethod("format", listOf("String", "Object", "Object"), "java.lang.String")

            every { classpathService.getMethods("java.lang.String") } returns
                listOf(method1Arg, method2Args, method3Args)

            val classpathUri = URI("jar:file:///path/to/rt.jar!/java/lang/String.class")
            every { compilationService.findClasspathClass("java.lang.String") } returns classpathUri

            // Capture which method is navigated to
            var navigatedMethod: String? = null
            coEvery {
                sourceNavigator.navigateToMethodSource(classpathUri, "java.lang.String", any())
            } answers {
                navigatedMethod = thirdArg()
                SourceNavigator.SourceResult.SourceLocation(
                    uri = URI("file:///jdk/src/String.java"),
                    className = "java.lang.String",
                    lineNumber = 100,
                )
            }

            val result = strategy.resolve(context)

            assertTrue(result.isRight())
            // FIXME: We need to verify the 3-arg overload was selected
            // Currently we can't distinguish which overload was chosen from the result
            assertEquals("format", navigatedMethod)
        }

        // FIXME(#843): TupleExpression argument handling is untested
        @Test
        @Disabled("TupleExpression argument count extraction - see issue #843")
        fun `extracts argument count from TupleExpression`() = runBlocking {
            val methodCall = mockk<MethodCallExpression>()
            val classExpr = mockk<ClassExpression>()
            val classNode = mockk<ClassNode>()
            // TupleExpression is used for named arguments: method(a: 1, b: 2)
            val tupleArgs = TupleExpression(
                listOf(ConstantExpression("arg1"), ConstantExpression("arg2")),
            )

            every { methodCall.methodAsString } returns "someMethod"
            every { methodCall.objectExpression } returns classExpr
            every { methodCall.isImplicitThis } returns false
            every { methodCall.arguments } returns tupleArgs
            every { classExpr.type } returns classNode
            every { classNode.name } returns "com.example.MyClass"

            val context = mockk<ResolutionContext>()
            every { context.targetNode } returns methodCall
            every { context.documentUri } returns URI("file:///test.groovy")

            val method = createReflectedMethod("someMethod", listOf("Object", "Object"), "com.example.MyClass")
            every { classpathService.getMethods("com.example.MyClass") } returns listOf(method)

            val classpathUri = URI("jar:file:///path/to/lib.jar!/com/example/MyClass.class")
            every { compilationService.findClasspathClass("com.example.MyClass") } returns classpathUri

            coEvery {
                sourceNavigator.navigateToMethodSource(classpathUri, "com.example.MyClass", "someMethod")
            } returns SourceNavigator.SourceResult.SourceLocation(
                uri = URI("file:///src/MyClass.java"),
                className = "com.example.MyClass",
                lineNumber = 50,
            )

            val result = strategy.resolve(context)

            assertTrue(result.isRight(), "Should handle TupleExpression arguments")
        }
    }

    // =============================================================================
    // Source Navigation Tests
    // =============================================================================

    @Nested
    inner class SourceNavigationTests {

        @Test
        fun `returns found with line number from source location`() = runBlocking {
            val methodCall = createStaticMethodCall("java.lang.String", "format", 1)

            val context = mockk<ResolutionContext>()
            every { context.targetNode } returns methodCall
            every { context.documentUri } returns URI("file:///test.groovy")

            val method = createReflectedMethod("format", listOf("String"), "java.lang.String")
            every { classpathService.getMethods("java.lang.String") } returns listOf(method)

            val classpathUri = URI("jar:file:///path/to/rt.jar!/java/lang/String.class")
            every { compilationService.findClasspathClass("java.lang.String") } returns classpathUri

            val expectedLine = 2946
            coEvery {
                sourceNavigator.navigateToMethodSource(classpathUri, "java.lang.String", "format")
            } returns SourceNavigator.SourceResult.SourceLocation(
                uri = URI("file:///jdk/src/String.java"),
                className = "java.lang.String",
                lineNumber = expectedLine,
            )

            val result = strategy.resolve(context)

            assertTrue(result.isRight())
            val definition = result.getOrNull()
            assertNotNull(definition)
            assertTrue(definition is DefinitionResolver.DefinitionResult.Binary)
            val binary = definition as DefinitionResolver.DefinitionResult.Binary
            // Line should be converted to 0-based for LSP
            assertEquals(expectedLine - 1, binary.range?.start?.line)
        }

        @Test
        fun `returns not found when source navigator returns BinaryOnly`() = runBlocking {
            val methodCall = createStaticMethodCall("java.lang.String", "format", 1)

            val context = mockk<ResolutionContext>()
            every { context.targetNode } returns methodCall
            every { context.documentUri } returns URI("file:///test.groovy")

            val method = createReflectedMethod("format", listOf("String"), "java.lang.String")
            every { classpathService.getMethods("java.lang.String") } returns listOf(method)

            val classpathUri = URI("jar:file:///path/to/rt.jar!/java/lang/String.class")
            every { compilationService.findClasspathClass("java.lang.String") } returns classpathUri

            coEvery {
                sourceNavigator.navigateToMethodSource(classpathUri, "java.lang.String", "format")
            } returns SourceNavigator.SourceResult.BinaryOnly(
                uri = classpathUri,
                className = "java.lang.String",
                reason = "Source JAR not found",
            )

            val result = strategy.resolve(context)

            // Should fall back to notFound so ClasspathResolutionStrategy can handle class-level nav
            assertTrue(result.isLeft())
        }

        @Test
        fun `works without source navigator (null)`() = runBlocking {
            // Create strategy without source navigator
            val strategyNoNav = MethodResolutionStrategy(compilationService, sourceNavigator = null)

            val methodCall = createStaticMethodCall("java.lang.String", "length", 0)

            val context = mockk<ResolutionContext>()
            every { context.targetNode } returns methodCall
            every { context.documentUri } returns URI("file:///test.groovy")

            val method = createReflectedMethod("length", emptyList(), "java.lang.String")
            every { classpathService.getMethods("java.lang.String") } returns listOf(method)
            every { compilationService.findClasspathClass("java.lang.String") } returns
                URI("jar:file:///rt.jar!/java/lang/String.class")

            val result = strategyNoNav.resolve(context)

            // Should return notFound (no source nav available), allowing fallback to class-level
            assertTrue(result.isLeft())
        }
    }

    // =============================================================================
    // Edge Cases & QA Tests
    // =============================================================================

    @Nested
    inner class EdgeCaseTests {

        // FIXME(#843): Private methods should not be resolved via this strategy
        @Test
        @Disabled("Private method visibility filtering - see issue #843")
        fun `does not match private methods`() = runBlocking {
            val methodCall = createStaticMethodCall("java.lang.String", "privateHelper", 0)

            val context = mockk<ResolutionContext>()
            every { context.targetNode } returns methodCall
            every { context.documentUri } returns URI("file:///test.groovy")

            val privateMethod = ReflectedMethod(
                name = "privateHelper",
                parameters = emptyList(),
                parameterNames = emptyList(),
                returnType = "void",
                declaringClass = "java.lang.String",
                isStatic = true,
                isPublic = false, // Private!
                doc = "",
            )
            every { classpathService.getMethods("java.lang.String") } returns listOf(privateMethod)

            val result = strategy.resolve(context)

            assertTrue(result.isLeft(), "Should not match private methods")
            val error = result.leftOrNull()
            assertTrue(error?.reason?.contains("not found") == true)
        }

        // TODO(#843): Test inherited method resolution
        @Test
        @Disabled("Inherited method resolution - see issue #843")
        fun `resolves inherited methods from superclass`() = runBlocking {
            // ArrayList.toString() should resolve to Object.toString() or AbstractCollection.toString()
            val methodCall = mockk<MethodCallExpression>()
            val varExpr = VariableExpression("list")

            every { methodCall.methodAsString } returns "toString"
            every { methodCall.objectExpression } returns varExpr
            every { methodCall.isImplicitThis } returns false
            every { methodCall.arguments } returns ArgumentListExpression(emptyList())

            // Mock type resolution to return ArrayList
            every { compilationService.getAst(any()) } returns mockk()

            val context = mockk<ResolutionContext>()
            every { context.targetNode } returns methodCall
            every { context.documentUri } returns URI("file:///test.groovy")

            // ArrayList doesn't define toString, it's inherited
            every { classpathService.getMethods("java.util.ArrayList") } returns emptyList()
            // But Object does
            val toStringMethod = createReflectedMethod("toString", emptyList(), "java.lang.Object")
            every { classpathService.getMethods("java.lang.Object") } returns listOf(toStringMethod)

            val result = strategy.resolve(context)

            // Currently this would fail - strategy doesn't walk inheritance chain
            assertTrue(result.isRight(), "Should resolve inherited method")
        }

        // TODO(#843): Varargs method matching
        @Test
        @Disabled("Varargs method matching - see issue #843")
        fun `matches varargs method with multiple arguments`() = runBlocking {
            // String.format(String, Object...) should match calls with any number of args
            val methodCall = createStaticMethodCall("java.lang.String", "format", 5) // format + 4 varargs

            val context = mockk<ResolutionContext>()
            every { context.targetNode } returns methodCall
            every { context.documentUri } returns URI("file:///test.groovy")

            // Only one format method with varargs
            val formatMethod = ReflectedMethod(
                name = "format",
                parameters = listOf("String", "Object..."), // Varargs
                parameterNames = listOf("format", "args"),
                returnType = "String",
                declaringClass = "java.lang.String",
                isStatic = true,
                isPublic = true,
                doc = "",
            )
            every { classpathService.getMethods("java.lang.String") } returns listOf(formatMethod)

            val classpathUri = URI("jar:file:///rt.jar!/java/lang/String.class")
            every { compilationService.findClasspathClass("java.lang.String") } returns classpathUri

            coEvery {
                sourceNavigator.navigateToMethodSource(any(), any(), any())
            } returns SourceNavigator.SourceResult.SourceLocation(
                uri = URI("file:///src/String.java"),
                className = "java.lang.String",
                lineNumber = 100,
            )

            val result = strategy.resolve(context)

            // Currently fails: 5 args != 2 params, even though second param is varargs
            assertTrue(result.isRight(), "Should match varargs method regardless of arg count")
        }

        // TODO(#843): GDK extension method resolution
        @Test
        @Disabled("GDK extension method resolution - see issue #843")
        fun `resolves GDK extension methods`() = runBlocking {
            // "hello".each { } - each is a GDK method, not a String method
            val methodCall = mockk<MethodCallExpression>()
            val varExpr = VariableExpression("str")

            every { methodCall.methodAsString } returns "each"
            every { methodCall.objectExpression } returns varExpr
            every { methodCall.isImplicitThis } returns false
            every { methodCall.arguments } returns ArgumentListExpression(emptyList())

            val context = mockk<ResolutionContext>()
            every { context.targetNode } returns methodCall
            every { context.documentUri } returns URI("file:///test.groovy")

            // String doesn't have each()
            every { classpathService.getMethods("java.lang.String") } returns emptyList()

            val result = strategy.resolve(context)

            // This test documents that GDK methods are NOT currently handled by this strategy
            // A separate GdkMethodResolutionStrategy would be needed
            assertTrue(result.isLeft(), "GDK methods are not handled by this strategy")
        }
    }

    // =============================================================================
    // Helper Methods
    // =============================================================================

    private fun createReflectedMethod(
        name: String,
        parameters: List<String>,
        declaringClass: String,
        isStatic: Boolean = false,
    ) = ReflectedMethod(
        name = name,
        parameters = parameters,
        parameterNames = parameters.mapIndexed { i, _ -> "arg$i" },
        returnType = "Object",
        declaringClass = declaringClass,
        isStatic = isStatic,
        isPublic = true,
        doc = "",
    )

    private fun createStaticMethodCall(className: String, methodName: String, argCount: Int): MethodCallExpression {
        val methodCall = mockk<MethodCallExpression>()
        val classExpr = mockk<ClassExpression>()
        val classNode = mockk<ClassNode>()
        val args = ArgumentListExpression(
            (0 until argCount).map { ConstantExpression("arg$it") },
        )

        every { methodCall.methodAsString } returns methodName
        every { methodCall.objectExpression } returns classExpr
        every { methodCall.isImplicitThis } returns false
        every { methodCall.arguments } returns args
        every { classExpr.type } returns classNode
        every { classNode.name } returns className

        return methodCall
    }
}

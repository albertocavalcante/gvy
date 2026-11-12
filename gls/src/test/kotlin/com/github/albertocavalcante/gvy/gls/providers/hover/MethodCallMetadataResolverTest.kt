package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.gvy.gls.services.ClasspathService
import com.github.albertocavalcante.gvy.gls.services.GdkExtensionMethod
import com.github.albertocavalcante.gvy.gls.services.GroovyGdkProvider
import com.github.albertocavalcante.gvy.gls.services.QualifiedGdkMethod
import com.github.albertocavalcante.gvy.gls.services.ReflectedMethod
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import io.mockk.every
import io.mockk.mockk
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class MethodCallMetadataResolverTest {

    private fun mockCall(): MethodCallExpression = mockk {
        every { methodTarget } returns null
        every { getNodeMetaData<Any>("targetMethod") } returns null
        every { isSpreadSafe } returns false
    }

    private lateinit var classpathService: ClasspathService
    private lateinit var gdkProvider: GroovyGdkProvider
    private lateinit var semanticResolver: SemanticTypeResolver
    private lateinit var resolver: MethodCallMetadataResolver

    @BeforeEach
    fun setup() {
        classpathService = mockk()
        gdkProvider = mockk {
            every { getQualifiedMethodsForType(any()) } answers {
                getMethodsForType(firstArg()).map { method ->
                    QualifiedGdkMethod(
                        method,
                        method.parameterTypes.map { type ->
                            when (type) {
                                "String" -> "java.lang.String"
                                "Object" -> "java.lang.Object"
                                else -> type
                            }
                        },
                    )
                }
            }
        }
        semanticResolver = mockk()
        resolver = MethodCallMetadataResolver(classpathService, gdkProvider, semanticResolver)
    }

    @Test
    fun `resolver is instantiated correctly`() {
        // Simple smoke test to verify the resolver can be instantiated
        assertNotNull(resolver)
    }

    @Test
    fun `resolveMethodCall returns null for null method name`() {
        val call = mockCall()
        every { call.methodAsString } returns null

        val result = resolver.resolveMethodCall(call, null)

        assertNull(result)
    }

    @Test
    fun `resolveMethodCall returns null for blank method name`() {
        val call = mockCall()
        every { call.methodAsString } returns ""

        val result = resolver.resolveMethodCall(call, null)

        assertNull(result)
    }

    @Test
    fun `resolveMethodCall returns null for implicit this calls`() {
        val call = mockCall()
        every { call.methodAsString } returns "someMethod"
        every { call.objectExpression } returns mockk()
        every { call.isImplicitThis } returns true

        val result = resolver.resolveMethodCall(call, null)

        assertNull(result)
    }

    @Test
    fun `resolves GDK method with correct metadata`() {
        // Setup: Create a mock method call for "hello".takeAfter("h")
        val call = mockCall()
        val receiver = VariableExpression("hello")
        val args = ArgumentListExpression(listOf(ConstantExpression("h")))

        every { call.methodAsString } returns "takeAfter"
        every { call.objectExpression } returns receiver
        every { call.isImplicitThis } returns false
        every { call.arguments } returns args

        // Mock semantic resolver to return String type
        every { semanticResolver.resolveType(receiver, null) } returns mockk()
        every { semanticResolver.formatSemanticType(any()) } returns "java.lang.String"

        // Mock GDK provider to return a GDK method
        val gdkMethod = GdkExtensionMethod(
            name = "takeAfter",
            parameterTypes = listOf("String"),
            parameterNames = listOf("prefix"),
            returnType = "String",
            originClass = "org.codehaus.groovy.runtime.StringGroovyMethods",
            doc = "Checks if string starts with prefix",
        )
        every { gdkProvider.getMethodsForType("java.lang.String") } returns listOf(gdkMethod)
        every { classpathService.getMethods(any()) } returns emptyList()

        val result = resolver.resolveMethodCall(call, null)

        assertNotNull(result)
        assertEquals("String takeAfter(String prefix)", result?.signature)
        assertEquals("org.codehaus.groovy.runtime.StringGroovyMethods", result?.declaringClass)
        assertEquals("String", result?.returnType)
        assertEquals("Checks if string starts with prefix", result?.documentation)
    }

    @Test
    fun `resolves classpath method when GDK not found`() {
        // Setup: Create a mock method call
        val call = mockCall()
        val classExpr = ClassExpression(ClassNode("demo.Factory", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE))
        val args = ArgumentListExpression(listOf(ConstantExpression(42)))

        every { call.methodAsString } returns "valueOf"
        every { call.objectExpression } returns classExpr
        every { call.isImplicitThis } returns false
        every { call.arguments } returns args

        // Mock GDK provider to return empty (no GDK method)
        every { gdkProvider.getMethodsForType("demo.Factory") } returns emptyList()

        // Mock classpath service to return a method
        val reflectedMethod = ReflectedMethod(
            name = "valueOf",
            parameters = listOf("int"),
            parameterNames = listOf("i"),
            returnType = "String",
            declaringClass = "demo.Factory",
            isStatic = true,
            isPublic = true,
            doc = "Returns the string representation",
        )
        every { classpathService.getMethods("demo.Factory") } returns listOf(reflectedMethod)

        val result = resolver.resolveMethodCall(call, null)

        assertNotNull(result)
        assertEquals("String valueOf(int i)", result?.signature)
        assertEquals("demo.Factory", result?.declaringClass)
    }

    @Test
    fun `matches method by argument count`() {
        // Setup: Create a mock method call with 2 arguments
        val call = mockCall()
        val receiver = VariableExpression("list")
        val args = ArgumentListExpression(listOf(ConstantExpression(0), ConstantExpression("item")))

        every { call.methodAsString } returns "putAt"
        every { call.objectExpression } returns receiver
        every { call.isImplicitThis } returns false
        every { call.arguments } returns args

        // Mock semantic resolver
        every { semanticResolver.resolveType(receiver, null) } returns mockk()
        every { semanticResolver.formatSemanticType(any()) } returns "java.util.List"

        // Mock GDK provider with overloaded methods
        val addMethod1 = GdkExtensionMethod(
            name = "putAt",
            parameterTypes = listOf("Object"),
            parameterNames = listOf("element"),
            returnType = "boolean",
            originClass = "DefaultGroovyMethods",
            doc = "Adds one element",
        )
        val addMethod2 = GdkExtensionMethod(
            name = "putAt",
            parameterTypes = listOf("int", "Object"),
            parameterNames = listOf("index", "element"),
            returnType = "void",
            originClass = "DefaultGroovyMethods",
            doc = "Adds element at index",
        )
        every { gdkProvider.getMethodsForType("java.util.List") } returns listOf(addMethod1, addMethod2)
        every { classpathService.getMethods(any()) } returns emptyList()

        val result = resolver.resolveMethodCall(call, null)

        // Should match the method with 2 parameters
        assertNotNull(result)
        assertEquals("void putAt(int index, Object element)", result?.signature)
        assertTrue(result?.documentation?.contains("at index") == true)
    }

    @Test
    fun `returns null when method not found anywhere`() {
        // Setup: Create a mock method call
        val call = mockCall()
        val receiver = VariableExpression("obj")
        val args = ArgumentListExpression(emptyList())

        every { call.methodAsString } returns "unknownMethod"
        every { call.objectExpression } returns receiver
        every { call.isImplicitThis } returns false
        every { call.arguments } returns args

        // Mock semantic resolver
        every { semanticResolver.resolveType(receiver, null) } returns mockk()
        every { semanticResolver.formatSemanticType(any()) } returns "java.lang.Object"

        // Mock both providers to return empty
        every { gdkProvider.getMethodsForType("java.lang.Object") } returns emptyList()
        every { classpathService.getMethods("java.lang.Object") } returns emptyList()

        val result = resolver.resolveMethodCall(call, null)

        assertNull(result)
    }
}

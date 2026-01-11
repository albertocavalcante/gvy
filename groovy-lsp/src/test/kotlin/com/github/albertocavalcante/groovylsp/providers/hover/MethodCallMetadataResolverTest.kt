package com.github.albertocavalcante.groovylsp.providers.hover

import com.github.albertocavalcante.groovylsp.services.ClasspathService
import com.github.albertocavalcante.groovylsp.services.GdkExtensionMethod
import com.github.albertocavalcante.groovylsp.services.GroovyGdkProvider
import com.github.albertocavalcante.groovylsp.services.ReflectedMethod
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import io.mockk.every
import io.mockk.mockk
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

class MethodCallMetadataResolverTest {

    private lateinit var classpathService: ClasspathService
    private lateinit var gdkProvider: GroovyGdkProvider
    private lateinit var semanticResolver: SemanticTypeResolver
    private lateinit var resolver: MethodCallMetadataResolver

    @BeforeEach
    fun setup() {
        classpathService = mockk()
        gdkProvider = mockk()
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
        val call = mockk<MethodCallExpression>()
        every { call.methodAsString } returns null

        val result = resolver.resolveMethodCall(call, null)

        assertNull(result)
    }

    @Test
    fun `resolveMethodCall returns null for blank method name`() {
        val call = mockk<MethodCallExpression>()
        every { call.methodAsString } returns ""

        val result = resolver.resolveMethodCall(call, null)

        assertNull(result)
    }

    @Test
    fun `resolveMethodCall returns null for implicit this calls`() {
        val call = mockk<MethodCallExpression>()
        every { call.methodAsString } returns "someMethod"
        every { call.objectExpression } returns mockk()
        every { call.isImplicitThis } returns true

        val result = resolver.resolveMethodCall(call, null)

        assertNull(result)
    }

    @Test
    fun `resolves GDK method with correct metadata`() {
        // Setup: Create a mock method call for "hello".startsWith("h")
        val call = mockk<MethodCallExpression>()
        val receiver = VariableExpression("hello")
        val args = ArgumentListExpression(listOf(ConstantExpression("h")))

        every { call.methodAsString } returns "startsWith"
        every { call.objectExpression } returns receiver
        every { call.isImplicitThis } returns false
        every { call.arguments } returns args

        // Mock semantic resolver to return String type
        every { semanticResolver.resolveType(receiver, null) } returns mockk()
        every { semanticResolver.formatSemanticType(any()) } returns "java.lang.String"

        // Mock GDK provider to return a GDK method
        val gdkMethod = GdkExtensionMethod(
            name = "startsWith",
            parameterTypes = listOf("String"),
            parameterNames = listOf("prefix"),
            returnType = "boolean",
            originClass = "org.codehaus.groovy.runtime.StringGroovyMethods",
            doc = "Checks if string starts with prefix",
        )
        every { gdkProvider.getMethodsForType("java.lang.String") } returns listOf(gdkMethod)
        every { classpathService.getMethods(any()) } returns emptyList()

        val result = resolver.resolveMethodCall(call, null)

        assertNotNull(result)
        assertEquals("boolean startsWith(String prefix)", result?.signature)
        assertEquals("org.codehaus.groovy.runtime.StringGroovyMethods", result?.declaringClass)
        assertEquals("boolean", result?.returnType)
        assertEquals("Checks if string starts with prefix", result?.documentation)
    }

    @Test
    fun `resolves classpath method when GDK not found`() {
        // Setup: Create a mock method call
        val call = mockk<MethodCallExpression>()
        val classExpr = mockk<ClassExpression>()
        val classNode = mockk<ClassNode>()
        val args = ArgumentListExpression(listOf(ConstantExpression(42)))

        every { call.methodAsString } returns "valueOf"
        every { call.objectExpression } returns classExpr
        every { call.isImplicitThis } returns false
        every { call.arguments } returns args
        every { classExpr.type } returns classNode
        every { classNode.name } returns "java.lang.String"

        // Mock GDK provider to return empty (no GDK method)
        every { gdkProvider.getMethodsForType("java.lang.String") } returns emptyList()

        // Mock classpath service to return a method
        val reflectedMethod = ReflectedMethod(
            name = "valueOf",
            parameters = listOf("int"),
            parameterNames = listOf("i"),
            returnType = "String",
            declaringClass = "java.lang.String",
            isStatic = true,
            isPublic = true,
            doc = "Returns the string representation",
        )
        every { classpathService.getMethods("java.lang.String") } returns listOf(reflectedMethod)

        val result = resolver.resolveMethodCall(call, null)

        assertNotNull(result)
        assertEquals("String valueOf(int i)", result?.signature)
        assertEquals("java.lang.String", result?.declaringClass)
    }

    @Test
    fun `matches method by argument count`() {
        // Setup: Create a mock method call with 2 arguments
        val call = mockk<MethodCallExpression>()
        val receiver = VariableExpression("list")
        val args = ArgumentListExpression(listOf(ConstantExpression(0), ConstantExpression("item")))

        every { call.methodAsString } returns "add"
        every { call.objectExpression } returns receiver
        every { call.isImplicitThis } returns false
        every { call.arguments } returns args

        // Mock semantic resolver
        every { semanticResolver.resolveType(receiver, null) } returns mockk()
        every { semanticResolver.formatSemanticType(any()) } returns "java.util.List"

        // Mock GDK provider with overloaded methods
        val addMethod1 = GdkExtensionMethod(
            name = "add",
            parameterTypes = listOf("Object"),
            parameterNames = listOf("element"),
            returnType = "boolean",
            originClass = "DefaultGroovyMethods",
            doc = "Adds one element",
        )
        val addMethod2 = GdkExtensionMethod(
            name = "add",
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
        assertEquals("void add(int index, Object element)", result?.signature)
        assertTrue(result?.documentation?.contains("at index") == true)
    }

    @Test
    fun `returns null when method not found anywhere`() {
        // Setup: Create a mock method call
        val call = mockk<MethodCallExpression>()
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

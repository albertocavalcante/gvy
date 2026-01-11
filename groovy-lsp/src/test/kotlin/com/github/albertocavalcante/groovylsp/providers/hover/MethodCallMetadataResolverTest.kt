package com.github.albertocavalcante.groovylsp.providers.hover

import com.github.albertocavalcante.groovylsp.services.ClasspathService
import com.github.albertocavalcante.groovylsp.services.GroovyGdkProvider
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import io.mockk.every
import io.mockk.mockk
import org.codehaus.groovy.ast.expr.MethodCallExpression
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
}

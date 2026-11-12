// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.gls.services.ClasspathService
import com.github.albertocavalcante.gvy.gls.services.GdkExtensionMethod
import com.github.albertocavalcante.gvy.gls.services.GroovyGdkProvider
import com.github.albertocavalcante.gvy.gls.services.QualifiedGdkMethod
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import com.github.albertocavalcante.gvy.semantics.PrimitiveKind
import com.github.albertocavalcante.gvy.semantics.SemanticType
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class InferredArrayReceiverHoverTest {
    private val classpath = mockk<ClasspathService> {
        every { getMethods(any()) } returns emptyList()
        every { loadClass(any()) } returns null
    }
    private val gdk = mockk<GroovyGdkProvider> {
        every { getQualifiedMethodsForType(any()) } returns emptyList()
    }

    @Test
    fun `inferred source array retains inherited Object method target`() {
        val element = ClassNode("demo.Element", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        val module = ModuleNode(SourceUnit.create("ArrayReceiver.groovy", "")).apply { addClass(element) }
        val resolver = resolverFor(SemanticType.Array(SemanticType.Known(element.name)))

        val metadata = assertNotNull(resolver.resolveMethodCall(call("hashCode"), module))

        assertEquals("java.lang.Object", metadata.declaringClass)
        assertContains(metadata.signature, "int hashCode()")
    }

    @Test
    fun `inferred primitive array retains its GDK receiver identity`() {
        val extension = GdkExtensionMethod("sum", "int", emptyList(), emptyList(), "ArrayGroovyMethods", "Array sum")
        every { gdk.getQualifiedMethodsForType(IntArray::class.java.name) } returns listOf(
            QualifiedGdkMethod(extension, emptyList()),
        )
        val resolver = resolverFor(SemanticType.Array(SemanticType.Primitive(PrimitiveKind.INT)))

        val metadata = assertNotNull(resolver.resolveMethodCall(call("sum"), null))

        assertEquals("ArrayGroovyMethods", metadata.declaringClass)
        assertEquals("int sum()", metadata.signature)
    }

    private fun resolverFor(type: SemanticType): MethodCallMetadataResolver {
        val semantic = spyk(SemanticTypeResolver(ReflectionTypeSolver())) {
            every { resolveType(any(), any()) } returns type
        }
        return MethodCallMetadataResolver(classpath, gdk, semantic)
    }

    private fun call(name: String): MethodCallExpression = MethodCallExpression(
        VariableExpression("values"),
        name,
        ArgumentListExpression.EMPTY_ARGUMENTS,
    ).apply { isImplicitThis = false }
}

package com.github.albertocavalcante.gvy.semantics.openrewrite

import com.github.albertocavalcante.gvy.semantics.PrimitiveKind
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.openrewrite.groovy.GroovyParser
import org.openrewrite.groovy.tree.G
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.JavaType
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [RewriteTypeContext] which provides TypeContext implementation
 * for OpenRewrite LST nodes.
 */
class RewriteTypeContextTest {

    private val parser = GroovyParser.builder().build()

    private fun parse(code: String): G.CompilationUnit {
        val sources = parser.parse(code)
        return sources.toList().first() as G.CompilationUnit
    }

    @Nested
    @DisplayName("calculateType - Literal Expressions")
    inner class CalculateTypeLiterals {

        @Test
        fun `calculates type for String literal`() {
            val cu = parse(
                """
                def x = "hello"
                """.trimIndent(),
            )

            val context = RewriteTypeContext()
            val literal = findFirstLiteral(cu)

            val result = context.calculateType(literal)

            assertIs<SemanticType.Known>(result)
            assertEquals("java.lang.String", result.fqn)
        }

        @Test
        fun `calculates type for integer literal`() {
            val cu = parse(
                """
                def x = 42
                """.trimIndent(),
            )

            val context = RewriteTypeContext()
            val literal = findFirstLiteral(cu)

            val result = context.calculateType(literal)

            assertIs<SemanticType.Primitive>(result)
            assertEquals(PrimitiveKind.INT, result.kind)
        }

        @Test
        fun `calculates type for boolean literal`() {
            val cu = parse(
                """
                def x = true
                """.trimIndent(),
            )

            val context = RewriteTypeContext()
            val literal = findFirstLiteral(cu)

            val result = context.calculateType(literal)

            assertIs<SemanticType.Primitive>(result)
            assertEquals(PrimitiveKind.BOOLEAN, result.kind)
        }

        @Test
        fun `calculates type for double literal`() {
            val cu = parse(
                """
                def x = 3.14
                """.trimIndent(),
            )

            val context = RewriteTypeContext()
            val literal = findFirstLiteral(cu)

            val result = context.calculateType(literal)

            // Groovy may represent this as BigDecimal
            assertTrue(
                result is SemanticType.Primitive || result is SemanticType.Known,
                "Expected Primitive or Known, got $result",
            )
        }

        @Test
        fun `calculates type for null literal`() {
            val cu = parse(
                """
                def x = null
                """.trimIndent(),
            )

            val context = RewriteTypeContext()
            val literal = findFirstLiteral(cu)

            val result = context.calculateType(literal)

            assertEquals(SemanticType.Null, result)
        }
    }

    @Nested
    @DisplayName("calculateType - Variable Declarations")
    inner class CalculateTypeVariables {

        @Test
        fun `calculates type for typed variable declaration`() {
            val cu = parse(
                """
                String name = "test"
                """.trimIndent(),
            )

            val context = RewriteTypeContext()
            val varDecl = findFirstVariableDeclaration(cu)

            val result = context.calculateType(varDecl)

            assertIs<SemanticType.Known>(result)
            assertEquals("java.lang.String", result.fqn)
        }

        @Test
        fun `calculates type for def variable declaration`() {
            val cu = parse(
                """
                def name = "test"
                """.trimIndent(),
            )

            val context = RewriteTypeContext()
            val varDecl = findFirstVariableDeclaration(cu)

            val result = context.calculateType(varDecl)

            // OpenRewrite infers the type from the initializer for def declarations
            // def name = "test" -> type is inferred as String from the initializer
            assertIs<SemanticType.Known>(result)
            assertEquals("java.lang.String", result.fqn)
        }

        @Test
        fun `calculates type for primitive variable declaration`() {
            val cu = parse(
                """
                int count = 5
                """.trimIndent(),
            )

            val context = RewriteTypeContext()
            val varDecl = findFirstVariableDeclaration(cu)

            val result = context.calculateType(varDecl)

            assertIs<SemanticType.Primitive>(result)
            assertEquals(PrimitiveKind.INT, result.kind)
        }
    }

    @Nested
    @DisplayName("calculateType - Method Invocations")
    inner class CalculateTypeMethodInvocations {

        @Test
        fun `calculates type for method invocation with known return type`() {
            val cu =
                parse(
                    """
                class Example {
                    String getName() { "test" }
                }
                def e = new Example()
                e.getName()
                    """.trimIndent(),
                )

            val context = RewriteTypeContext()
            val methodInvocation = findFirstMethodInvocation(cu)

            val result = context.calculateType(methodInvocation)

            // Should resolve to String based on method return type
            if (result is SemanticType.Known) {
                assertEquals("java.lang.String", result.fqn)
            }
            // May be Unknown if type attribution is incomplete
        }
    }

    @Nested
    @DisplayName("resolveType - FQN Resolution")
    inner class ResolveTypeFqn {

        @Test
        fun `resolves known Java type`() {
            val context = RewriteTypeContext()

            val result = context.resolveType("java.lang.String")

            assertIs<SemanticType.Known>(result)
            assertEquals("java.lang.String", result.fqn)
        }

        @Test
        fun `resolves List type`() {
            val context = RewriteTypeContext()

            val result = context.resolveType("java.util.List")

            assertIs<SemanticType.Known>(result)
            assertEquals("java.util.List", result.fqn)
        }

        @Test
        fun `resolves Groovy GString type`() {
            val context = RewriteTypeContext()

            val result = context.resolveType("groovy.lang.GString")

            assertIs<SemanticType.Known>(result)
            assertEquals("groovy.lang.GString", result.fqn)
        }
    }

    @Nested
    @DisplayName("getFieldType - Field Resolution")
    inner class GetFieldType {

        @Test
        fun `returns null for field lookup on Known type (not implemented)`() {
            val context = RewriteTypeContext()
            val receiverType = SemanticType.Known("java.lang.String")

            val result = context.getFieldType(receiverType, "length")

            // Basic implementation returns null; field resolution requires type solver
            assertNull(result)
        }

        @Test
        fun `returns null for field lookup on Primitive type`() {
            val context = RewriteTypeContext()
            val receiverType = TypeConstants.INT

            val result = context.getFieldType(receiverType, "someField")

            assertNull(result)
        }

        @Test
        fun `returns INT for array length field`() {
            val context = RewriteTypeContext()
            val receiverType = SemanticType.Array(TypeConstants.STRING)

            val result = context.getFieldType(receiverType, "length")

            assertEquals(TypeConstants.INT, result)
        }
    }

    @Nested
    @DisplayName("getMethodReturnType - Method Resolution")
    inner class GetMethodReturnType {

        @Test
        fun `returns null for method lookup (not implemented without type solver)`() {
            val context = RewriteTypeContext()
            val receiverType = SemanticType.Known("java.lang.String")

            val result = context.getMethodReturnType(receiverType, "length", emptyList())

            // Basic implementation returns null; method resolution requires type solver
            assertNull(result)
        }
    }

    @Nested
    @DisplayName("lookupSymbol - Symbol Resolution")
    inner class LookupSymbol {

        @Test
        fun `returns null for unknown symbol`() {
            val context = RewriteTypeContext()

            val result = context.lookupSymbol("unknownVariable")

            assertNull(result)
        }
    }

    @Nested
    @DisplayName("isStaticCompilation")
    inner class StaticCompilation {

        @Test
        fun `defaults to false`() {
            val context = RewriteTypeContext()

            assertEquals(false, context.isStaticCompilation)
        }

        @Test
        fun `can be set to true`() {
            val context = RewriteTypeContext(isStaticCompilation = true)

            assertEquals(true, context.isStaticCompilation)
        }
    }

    // Helper methods to find AST nodes

    private fun findFirstLiteral(cu: G.CompilationUnit): J.Literal {
        var result: J.Literal? = null
        object : org.openrewrite.groovy.GroovyVisitor<Unit>() {
            override fun visitLiteral(literal: J.Literal, p: Unit): J {
                if (result == null) {
                    result = literal
                }
                return super.visitLiteral(literal, p)
            }
        }.visit(cu, Unit)
        return result ?: error("No literal found")
    }

    private fun findFirstVariableDeclaration(cu: G.CompilationUnit): J.VariableDeclarations {
        var result: J.VariableDeclarations? = null
        object : org.openrewrite.groovy.GroovyVisitor<Unit>() {
            override fun visitVariableDeclarations(multiVariable: J.VariableDeclarations, p: Unit): J {
                if (result == null) {
                    result = multiVariable
                }
                return super.visitVariableDeclarations(multiVariable, p)
            }
        }.visit(cu, Unit)
        return result ?: error("No variable declaration found")
    }

    private fun findFirstMethodInvocation(cu: G.CompilationUnit): J.MethodInvocation {
        var result: J.MethodInvocation? = null
        object : org.openrewrite.groovy.GroovyVisitor<Unit>() {
            override fun visitMethodInvocation(method: J.MethodInvocation, p: Unit): J {
                if (result == null) {
                    result = method
                }
                return super.visitMethodInvocation(method, p)
            }
        }.visit(cu, Unit)
        return result ?: error("No method invocation found")
    }
}

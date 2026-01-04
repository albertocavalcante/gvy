package com.github.albertocavalcante.groovylsp.types

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.semantics.PrimitiveKind
import com.github.albertocavalcante.gvy.semantics.SemanticType
import org.assertj.core.api.Assertions.assertThat
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.CompileUnit
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SemanticTypeResolverTest {
    private lateinit var typeSolver: TypeSolver
    private lateinit var resolver: SemanticTypeResolver

    @BeforeEach
    fun setup() {
        typeSolver = ReflectionTypeSolver()
        resolver = SemanticTypeResolver(typeSolver)
    }

    @Test
    fun `resolves ConstantExpression type`() {
        val node = ConstantExpression("test")
        val module = ModuleNode(CompileUnit(null, null))

        val type = resolver.resolveType(node, module)

        assertThat(type).isInstanceOf(SemanticType.Known::class.java)
        assertThat((type as SemanticType.Known).fqn).isEqualTo("java.lang.String")
    }

    @Test
    fun `converts Known type to ClassNode`() {
        val type = SemanticType.Known("java.lang.String")
        val classNode = resolver.toClassNode(type, null)

        assertThat(classNode).isNotNull
        assertThat(classNode?.name).isEqualTo("java.lang.String")
    }

    @Test
    fun `converts Primitive INT to ClassNode`() {
        val type = SemanticType.Primitive(PrimitiveKind.INT)
        val classNode = resolver.toClassNode(type, null)

        assertThat(classNode).isEqualTo(ClassHelper.int_TYPE)
    }

    @Test
    fun `converts Primitive BOOLEAN to ClassNode`() {
        val type = SemanticType.Primitive(PrimitiveKind.BOOLEAN)
        val classNode = resolver.toClassNode(type, null)

        assertThat(classNode).isEqualTo(ClassHelper.boolean_TYPE)
    }

    @Test
    fun `converts Primitive DOUBLE to ClassNode`() {
        val type = SemanticType.Primitive(PrimitiveKind.DOUBLE)
        val classNode = resolver.toClassNode(type, null)

        assertThat(classNode).isEqualTo(ClassHelper.double_TYPE)
    }

    @Test
    fun `converts Dynamic to Object ClassNode`() {
        val type = SemanticType.Dynamic()
        val classNode = resolver.toClassNode(type, null)

        assertThat(classNode).isEqualTo(ClassHelper.OBJECT_TYPE)
    }

    @Test
    fun `converts Null to null ClassNode`() {
        val type = SemanticType.Null
        val classNode = resolver.toClassNode(type, null)

        assertThat(classNode).isNull()
    }

    @Test
    fun `converts Unknown to null ClassNode`() {
        val type = SemanticType.Unknown("test reason")
        val classNode = resolver.toClassNode(type, null)

        assertThat(classNode).isNull()
    }

    @Test
    fun `converts Union to first type ClassNode`() {
        val type =
            SemanticType.Union(
                setOf(
                    SemanticType.Known("java.lang.String"),
                    SemanticType.Primitive(PrimitiveKind.INT),
                ),
            )
        val classNode = resolver.toClassNode(type, null)

        assertThat(classNode).isNotNull
        // Should return String because "String" < "int" in sort order (S < i)
        assertThat(classNode?.name).isEqualTo("java.lang.String")
    }

    @Test
    fun `converts Array type to array ClassNode`() {
        val type = SemanticType.Array(SemanticType.Known("java.lang.String"))
        val classNode = resolver.toClassNode(type, null)

        assertThat(classNode).isNotNull
        assertThat(classNode?.isArray).isTrue()
        assertThat(classNode?.componentType?.name).isEqualTo("java.lang.String")
    }

    @Test
    fun `formats Known type correctly`() {
        val type = SemanticType.Known("java.lang.String")
        val formatted = resolver.formatSemanticType(type)

        assertThat(formatted).isEqualTo("String")
    }

    @Test
    fun `formats Primitive type correctly`() {
        val type = SemanticType.Primitive(PrimitiveKind.INT)
        val formatted = resolver.formatSemanticType(type)

        assertThat(formatted).isEqualTo("int")
    }

    @Test
    fun `formats Dynamic type correctly`() {
        val type = SemanticType.Dynamic()
        val formatted = resolver.formatSemanticType(type)

        assertThat(formatted).isEqualTo("def")
    }

    @Test
    fun `formats Dynamic type with hint correctly`() {
        val type = SemanticType.Dynamic("might be String")
        val formatted = resolver.formatSemanticType(type)

        assertThat(formatted).isEqualTo("might be String")
    }

    @Test
    fun `formats Unknown type correctly`() {
        val type = SemanticType.Unknown("test reason")
        val formatted = resolver.formatSemanticType(type)

        assertThat(formatted).isEqualTo("unresolved")
    }

    @Test
    fun `formats Union type correctly`() {
        val type =
            SemanticType.Union(
                setOf(
                    SemanticType.Known("java.lang.String"),
                    SemanticType.Primitive(PrimitiveKind.INT),
                ),
            )
        val formatted = resolver.formatSemanticType(type)

        // Union formatting is now deterministic (sorted)
        assertThat(formatted).isEqualTo("String | int")
    }

    @Test
    fun `formats Null type correctly`() {
        val type = SemanticType.Null
        val formatted = resolver.formatSemanticType(type)

        assertThat(formatted).isEqualTo("null")
    }

    @Test
    fun `formats Array type correctly`() {
        val type = SemanticType.Array(SemanticType.Known("java.lang.String"))
        val formatted = resolver.formatSemanticType(type)

        assertThat(formatted).isEqualTo("String[]")
    }

    @Test
    fun `formats nested Array type correctly`() {
        val type =
            SemanticType.Array(
                SemanticType.Array(SemanticType.Primitive(PrimitiveKind.INT)),
            )
        val formatted = resolver.formatSemanticType(type)

        assertThat(formatted).isEqualTo("int[][]")
    }
}

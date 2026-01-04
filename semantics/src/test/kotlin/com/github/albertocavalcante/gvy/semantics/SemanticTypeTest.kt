package com.github.albertocavalcante.gvy.semantics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SemanticTypeTest {

    @Test
    fun `known type toString`() {
        val type = SemanticType.Known("java.util.List", listOf(SemanticType.Known("java.lang.String")))
        assertEquals("java.util.List<java.lang.String>", type.toString())
    }

    @Test
    fun `known type without args toString`() {
        val type = SemanticType.Known("java.lang.String")
        assertEquals("java.lang.String", type.toString())
    }

    @Test
    fun `primitive type toString`() {
        assertEquals("int", SemanticType.Primitive(PrimitiveKind.INT).toString())
        assertEquals("boolean", SemanticType.Primitive(PrimitiveKind.BOOLEAN).toString())
    }

    @Test
    fun `dynamic type toString`() {
        assertEquals("dynamic", SemanticType.Dynamic().toString())
        assertEquals("dynamic(foo)", SemanticType.Dynamic("foo").toString())
    }

    @Test
    fun `unknown type toString`() {
        assertEquals("unknown(reason)", SemanticType.Unknown("reason").toString())
    }

    @Test
    fun `union type requires 2+ types`() {
        assertThrows<IllegalArgumentException> {
            SemanticType.Union(setOf(SemanticType.Known("String")))
        }

        val union = SemanticType.Union(
            setOf(
                SemanticType.Known("String"),
                SemanticType.Primitive(PrimitiveKind.INT),
            ),
        )
        // Set iteration order is not guaranteed, but toString usually follows it.
        // We just verify it contains |
        assert(union.toString().contains("|"))
    }

    @Test
    fun `data classes equality`() {
        assertEquals(
            SemanticType.Known("String"),
            SemanticType.Known("String"),
        )
        assertNotEquals(
            SemanticType.Known("String"),
            SemanticType.Known("Integer"),
        )
    }
}

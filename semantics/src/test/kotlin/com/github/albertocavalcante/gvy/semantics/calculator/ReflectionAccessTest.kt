package com.github.albertocavalcante.gvy.semantics.calculator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReflectionAccessTest {

    private class GetterBackedList(private val expressions: List<Any?>) {
        private var calls: Int = 0

        fun callCount(): Int = calls

        fun getExpressions(): List<Any?> {
            calls += 1

            return if (calls == 1) {
                expressions.toList()
            } else {
                buildList {
                    addAll(expressions)
                    add("unused")
                }
            }
        }
    }

    private class FieldOnlyList {
        @JvmField
        val expressions: List<Any?> = listOf("a", null, "b")
    }

    private class NotAList {
        private var calls: Int = 0

        fun getExpressions(): Any {
            calls += 1
            return if (calls == 1) 123 else 456
        }

        @JvmField
        val expressions: Any = "nope"
    }

    @Test
    fun `getListFromGetterOrField prefers getter list and filters nulls`() {
        val node = GetterBackedList(listOf(1, null, "x"))

        val result = ReflectionAccess.getListFromGetterOrField(node, "getExpressions", "expressions")

        assertEquals(listOf(1, "x"), result)
        assertEquals(1, node.callCount())
    }

    @Test
    fun `getListFromGetterOrField falls back to field list and filters nulls`() {
        val node = FieldOnlyList()

        val result = ReflectionAccess.getListFromGetterOrField(node, "getExpressions", "expressions")

        assertEquals(listOf("a", "b"), result)
    }

    @Test
    fun `getListFromGetterOrField returns null when neither getter nor field is a list`() {
        val node = NotAList()

        val result = ReflectionAccess.getListFromGetterOrField(node, "getExpressions", "expressions")

        assertNull(result)
    }
}

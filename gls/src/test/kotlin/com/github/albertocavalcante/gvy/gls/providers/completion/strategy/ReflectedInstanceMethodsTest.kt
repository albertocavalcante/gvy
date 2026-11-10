package com.github.albertocavalcante.gvy.gls.providers.completion.strategy

import org.junit.jupiter.api.Test
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReflectedInstanceMethodsTest {
    @Test
    fun `a generic bridge beside its typed target is dropped`() {
        val compareTo = reflectedInstanceMethods(Date::class.java).filter { it.name == "compareTo" }

        assertEquals(listOf(listOf(Date::class.java)), compareTo.map { it.parameterTypes.toList() })
    }

    @Test
    fun `a visibility bridge that is the only copy of a method is kept`() {
        // StringBuilder inherits these from package-private AbstractStringBuilder; getMethods() reports
        // only bridges for them.
        val names = reflectedInstanceMethods(StringBuilder::class.java).map { it.name }.toSet()

        assertTrue(names.containsAll(listOf("length", "charAt", "setLength", "capacity")), "found $names")
    }
}

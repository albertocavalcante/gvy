package com.github.albertocavalcante.groovyparser.resolution.types

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResolvedVoidTypeTest {

    @Test
    fun `describe returns void`() {
        assertEquals("void", ResolvedVoidType.describe())
    }

    @Test
    fun `isVoid returns true`() {
        assertTrue(ResolvedVoidType.isVoid())
    }

    @Test
    fun `isPrimitive returns false`() {
        assertFalse(ResolvedVoidType.isPrimitive())
    }

    @Test
    fun `void is only assignable from void`() {
        assertTrue(ResolvedVoidType.isAssignableBy(ResolvedVoidType))
        assertFalse(ResolvedVoidType.isAssignableBy(ResolvedPrimitiveType.INT))
    }
}

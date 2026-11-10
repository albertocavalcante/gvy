package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClassHierarchyTest {
    private fun cycle(): Pair<ClassNode, ClassNode> {
        val a = ClassNode("A", 1, ClassHelper.OBJECT_TYPE)
        val b = ClassNode("B", 1, a)
        a.superClass = b
        return a to b
    }

    @Test
    fun `queries on a superclass cycle terminate`() = assertTimeoutPreemptively(Duration.ofSeconds(10)) {
        val (a, b) = cycle()
        b.addField(FieldNode("f", 1, ClassHelper.STRING_TYPE, b, null))
        a.addMethod(
            MethodNode("m", 1, ClassHelper.VOID_TYPE, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, EmptyStatement()),
        )
        assertTrue(a.hasCyclicSuperClassChain())
        assertNull(a.findFieldSafe("missing"))
        assertNotNull(a.findFieldSafe("f"))
        assertTrue(a.methodsSafe("missing").isEmpty())
        assertEquals(1, a.methodsSafe("m").size)
        assertTrue(a.isDerivedFromSafe(b))
        assertFalse(a.isDerivedFromSafe(ClassHelper.STRING_TYPE))
        assertFalse(a.implementsInterfaceSafe(ClassHelper.MAP_TYPE))
    }

    @Test
    fun `acyclic chains follow Groovy's own rules`() {
        val base = ClassNode("Base", 1, ClassHelper.OBJECT_TYPE)
        val child = ClassNode("Child", 1, base)
        assertFalse(child.hasCyclicSuperClassChain())
        // Groovy treats every class as derived from Object and arrays by component type
        assertEquals(child.isDerivedFrom(ClassHelper.OBJECT_TYPE), child.isDerivedFromSafe(ClassHelper.OBJECT_TYPE))
        assertTrue(child.isDerivedFromSafe(ClassHelper.OBJECT_TYPE))
        assertTrue(child.isDerivedFromSafe(base))
        assertEquals(
            child.makeArray().isDerivedFrom(base.makeArray()),
            child.makeArray().isDerivedFromSafe(base.makeArray()),
        )
    }
}

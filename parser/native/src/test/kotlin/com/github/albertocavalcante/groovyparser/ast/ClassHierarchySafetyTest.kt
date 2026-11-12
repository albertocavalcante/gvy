// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.ast

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

class ClassHierarchySafetyTest {
    @Test
    fun `lookups on a superclass cycle terminate`() = assertTimeoutPreemptively(Duration.ofSeconds(10)) {
        val a = ClassNode("A", 1, ClassHelper.OBJECT_TYPE)
        val b = ClassNode("B", 1, a)
        a.superClass = b
        b.addField(FieldNode("f", 1, ClassHelper.STRING_TYPE, b, null))
        a.addMethod(
            MethodNode("m", 1, ClassHelper.VOID_TYPE, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, EmptyStatement()),
        )
        assertTrue(a.hasCyclicSuperClassChain())
        assertFalse(a.isScriptSafe())
        assertNull(a.findFieldSafe("missing"))
        assertNotNull(a.findFieldSafe("f"))
        assertTrue(a.methodsSafe("missing").isEmpty())
        assertEquals(1, a.methodsSafe("m").size)
        assertTrue(a.isDerivedFromSafe(b))
        assertFalse(a.implementsInterfaceSafe(ClassHelper.MAP_TYPE))
        assertEquals(listOf(b), a.superClassChain().toList())
    }

    @Test
    fun `acyclic chains defer to Groovy`() {
        val child = ClassNode("Child", 1, ClassNode("Base", 1, ClassHelper.OBJECT_TYPE))
        assertFalse(child.hasCyclicSuperClassChain())
        assertEquals(child.isDerivedFrom(ClassHelper.OBJECT_TYPE), child.isDerivedFromSafe(ClassHelper.OBJECT_TYPE))
        assertEquals(child.isScript, child.isScriptSafe())
    }
}

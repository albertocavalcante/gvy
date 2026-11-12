// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.native

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertEquals

/** Hostile or odd user-classpath classes must not break varargs display (#1418). */
class ClasspathVarargsRobustnessTest {
    /** A class with `static void run(<param>)`, flagged ACC_VARARGS whatever [param] is. */
    private fun classBytes(name: String, param: String): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        visitSource("Fixture.java", null)
        visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_VARARGS,
            "run",
            "($param)V",
            null,
            null,
        ).apply {
            visitCode()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 1)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()

    /** Defines [name] from [bytes] but serves [resource] bytes when something reads the class file back. */
    private class FixtureLoader(
        private val name: String,
        private val bytes: ByteArray,
        private val resource: ByteArray,
    ) : ClassLoader(ClasspathVarargsRobustnessTest::class.java.classLoader) {
        override fun findClass(className: String): Class<*> =
            if (className == name) defineClass(className, bytes, 0, bytes.size) else super.findClass(className)

        override fun getResourceAsStream(resourceName: String): InputStream? =
            if (resourceName == name.replace('.', '/') + ".class") ByteArrayInputStream(resource) else null
    }

    @Test
    fun `malformed class bytes read as not Groovy instead of throwing`() {
        val bytes = classBytes("Malformed", "[Ljava/lang/String;")
        // Valid header and constant pool, corrupted member tables: ASM fails with a runtime exception that is
        // neither an IOException nor an IllegalArgumentException.
        val truncated = bytes.copyOf().also { corrupt ->
            (corrupt.size - 40 until corrupt.size).forEach {
                corrupt[it] =
                    0x7F
            }
        }
        val type = FixtureLoader("Malformed", bytes, truncated).loadClass("Malformed")
        val run = type.getMethod("run", Array<String>::class.java)

        assertEquals(listOf("String..."), run.displayParameterTypes())
        assertEquals(listOf("String..."), run.displayParameterTypes(), "the answer is cached and stable")
    }

    @Test
    fun `a varargs flag on a non-array last parameter does not throw`() {
        val bytes = classBytes("NotAnArray", "Ljava/lang/String;")
        val type = FixtureLoader("NotAnArray", bytes, bytes).loadClass("NotAnArray")
        val run = type.getMethod("run", String::class.java)

        assertEquals(listOf("String"), run.displayParameterTypes())
    }
}

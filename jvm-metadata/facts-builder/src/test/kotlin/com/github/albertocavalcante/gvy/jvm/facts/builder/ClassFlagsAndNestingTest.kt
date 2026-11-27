// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import com.github.albertocavalcante.gvy.jvm.facts.FactsFormat
import org.junit.jupiter.api.Test
import org.objectweb.asm.ByteVector
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private const val NESTED = "p/Outer\$In"

class ClassFlagsAndNestingTest {
    @Test
    fun `takes nested class flags from the class's own InnerClasses entry`() {
        val protectedStatic = classFile(NESTED) {
            innerClass(NESTED, "p/Outer", "In", Opcodes.ACC_PROTECTED or Opcodes.ACC_STATIC)
        }
        val privateStatic = classFile(NESTED) {
            innerClass(NESTED, "p/Outer", "In", Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC)
        }

        val protectedRead = extractRead(protectedStatic, NESTED)
        val privateRead = extractRead(privateStatic, NESTED)

        // The class's own flags are PUBLIC | SUPER (0x21); the entry for itself wins.
        assertEquals(0x000C, protectedRead.fact.accessFlags)
        assertEquals(0x000A, privateRead.fact.accessFlags)
        assertEquals("In", protectedRead.fact.simpleName)
        assertEquals("p/Outer", protectedRead.fact.outerClass)
        assertEquals("p", protectedRead.fact.packageName)
    }

    @Test
    fun `uses own flags and no outer class when a class has no InnerClasses entry for itself`() {
        val bare = classFile("p/Odd\$Name", PUBLIC_SUPER or Opcodes.ACC_FINAL)
        val otherEntryOnly = classFile("p/Odd\$Name", PUBLIC_SUPER or Opcodes.ACC_FINAL) {
            innerClass("p/Other\$X", "p/Other", "X", Opcodes.ACC_PRIVATE)
            enclosingMethod("p/Elsewhere")
        }

        for (bytes in listOf(bare, otherEntryOnly)) {
            val fact = extractRead(bytes, "p/Odd\$Name").fact
            assertEquals(0x31, fact.accessFlags)
            assertNull(fact.outerClass)
            assertEquals("Odd\$Name", fact.simpleName)
        }
    }

    @Test
    fun `records anonymous classes with an empty simple name and the enclosing method's class as outer`() {
        val bytes = classFile("p/Outer\$1", Opcodes.ACC_SUPER) {
            enclosingMethod("p/Outer")
            innerClass("p/Outer\$1", null, null, 0)
        }

        val fact = extractRead(bytes, "p/Outer\$1").fact

        assertEquals("", fact.simpleName)
        assertEquals("p/Outer", fact.outerClass)
        assertEquals(0, fact.accessFlags)
    }

    @Test
    fun `records local classes with their simple name`() {
        val bytes = classFile("p/Outer\$1Local", Opcodes.ACC_SUPER) {
            enclosingMethod("p/Outer")
            innerClass("p/Outer\$1Local", null, "Local", 0)
        }

        val fact = extractRead(bytes, "p/Outer\$1Local").fact

        assertEquals("Local", fact.simpleName)
        assertEquals("p/Outer", fact.outerClass)
    }

    @Test
    fun `marks deprecation from the Deprecated attribute and from the annotation`() {
        val byAttribute = classFile("p/D") { deprecation = Deprecation.ATTRIBUTE }
        val byAnnotation = classFile("p/D") { deprecation = Deprecation.ANNOTATION }
        val notDeprecated = classFile("p/D")
        val nested = classFile(NESTED) {
            deprecation = Deprecation.ATTRIBUTE
            innerClass(NESTED, "p/Outer", "In", Opcodes.ACC_PROTECTED or Opcodes.ACC_STATIC)
        }

        assertEquals(FactsFormat.DEPRECATED_FLAG or 0x21, extractRead(byAttribute, "p/D").fact.accessFlags)
        assertEquals(FactsFormat.DEPRECATED_FLAG or 0x21, extractRead(byAnnotation, "p/D").fact.accessFlags)
        assertEquals(0x21, extractRead(notDeprecated, "p/D").fact.accessFlags)
        assertEquals(FactsFormat.DEPRECATED_FLAG or 0x0C, extractRead(nested, NESTED).fact.accessFlags)
    }

    @Test
    fun `never stores ASM's record pseudo flag`() {
        // ASM reports a Record attribute as 0x10000, which is the deprecation bit of the format.
        val access = PUBLIC_SUPER or Opcodes.ACC_FINAL
        val record = classFile("p/R", access) {
            superName = "java/lang/Record"
            recordComponent("x", "I")
        }
        val deprecatedRecord = classFile("p/R", access) {
            superName = "java/lang/Record"
            recordComponent("x", "I")
            deprecation = Deprecation.ATTRIBUTE
        }

        val flags = extractRead(record, "p/R").fact.accessFlags

        assertEquals(0x31, flags)
        assertEquals(0, flags and FactsFormat.DEPRECATED_FLAG)
        assertEquals(FactsFormat.DEPRECATED_FLAG or 0x31, extractRead(deprecatedRecord, "p/R").fact.accessFlags)
    }

    @Test
    fun `counts classes whose own and InnerClasses visibility disagree`() {
        val publicStatic = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC
        val synthetic = Opcodes.ACC_SYNTHETIC
        val cases = listOf(
            Triple(PUBLIC_SUPER, Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, true),
            Triple(PUBLIC_SUPER, publicStatic, false),
            Triple(PUBLIC_SUPER, Opcodes.ACC_PROTECTED or Opcodes.ACC_STATIC, false),
            Triple(Opcodes.ACC_SUPER, publicStatic, true),
            Triple(PUBLIC_SUPER or synthetic, Opcodes.ACC_PUBLIC or synthetic, false),
            Triple(PUBLIC_SUPER or synthetic, publicStatic, true),
        )
        for ((ownFlags, innerFlags, disagrees) in cases) {
            val bytes = classFile(NESTED, ownFlags) { innerClass(NESTED, "p/Outer", "In", innerFlags) }
            assertEquals(
                disagrees,
                extractRead(bytes, NESTED).visibilityDisagreement,
                "own 0x${ownFlags.toString(16)}, inner 0x${innerFlags.toString(16)}",
            )
        }
        assertFalse(extractRead(classFile("p/Top"), "p/Top").visibilityDisagreement)
    }

    @Test
    fun `uses the first InnerClasses entry when a class lists itself twice`() {
        // ClassWriter drops a repeated entry, so the attribute is written by hand.
        val bytes = classFile(NESTED) {
            rawAttribute("InnerClasses") { writer ->
                ByteVector()
                    .putShort(2)
                    .putShort(writer.newClass(NESTED))
                    .putShort(writer.newClass("p/Outer"))
                    .putShort(writer.newUTF8("In"))
                    .putShort(Opcodes.ACC_PROTECTED or Opcodes.ACC_STATIC)
                    .putShort(writer.newClass(NESTED))
                    .putShort(writer.newClass("p/Other"))
                    .putShort(writer.newUTF8("Second"))
                    .putShort(Opcodes.ACC_PRIVATE)
            }
        }

        val fact = extractRead(bytes, NESTED).fact

        assertEquals(0x000C, fact.accessFlags)
        assertEquals("In", fact.simpleName)
        assertEquals("p/Outer", fact.outerClass)
    }
}

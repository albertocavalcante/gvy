// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import com.github.albertocavalcante.gvy.jvm.facts.FactsFile
import com.github.albertocavalcante.gvy.jvm.facts.FactsWriter
import com.github.albertocavalcante.gvy.jvm.facts.LoadResult
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PUBLIC = Opcodes.ACC_PUBLIC
private const val LONE_HIGH = "\uD800"
private const val LONE_LOW = "\uDC00"

class UnrepresentableStringsTest {
    @Test
    fun `keeps the class but omits its types when the superclass name has an unpaired surrogate`() {
        val badSuper = classFile("p/A") { superName = "p/Base$LONE_HIGH" }
        val badInterface = classFile("p/A") { interfaces = listOf("p/Fine", "p/Worse$LONE_LOW") }
        val fine = classFile("p/A") { interfaces = listOf("p/Fine") }

        for (bytes in listOf(badSuper, badInterface)) {
            val read = extractRead(bytes, "p/A")
            assertEquals("p/A", read.fact.internalName)
            assertNull(read.types)
            assertTrue(read.typesOmitted)
        }
        val kept = extractRead(fine, "p/A")
        assertNotNull(kept.types)
        assertFalse(kept.typesOmitted)
    }

    @Test
    fun `drops a member whose name has an unpaired surrogate`() {
        val bytes = classFile("p/A") {
            method(PUBLIC, "bad$LONE_LOW", "()V")
            field(PUBLIC, "worse$LONE_HIGH", "I")
            method(PUBLIC, "fine", "()V")
        }

        val read = extractRead(bytes, "p/A")

        assertEquals(listOf("fine"), read.members().map { it.name })
        assertEquals(2, read.droppedMembers)
    }

    @Test
    fun `drops a member whose descriptor has an unpaired surrogate`() {
        val bytes = classFile("p/A") {
            method(PUBLIC, "bad", "(Lp/A$LONE_HIGH;)V")
            field(PUBLIC, "worse", "Lp/A$LONE_LOW;")
            field(PUBLIC, "fine", "Lp/A;")
        }

        val read = extractRead(bytes, "p/A")

        assertEquals(listOf("fine"), read.members().map { it.name })
        assertEquals(2, read.droppedMembers)
    }

    @Test
    fun `drops parameter names and signatures that cannot be represented`() {
        val bytes = classFile("p/A") {
            method(PUBLIC, "named", "(I)V") { parameters = listOf("a$LONE_HIGH") }
            method(PUBLIC, "generic", "(Ljava/util/List;)V", "(Ljava/util/List<TT$LONE_LOW;>;)V")
            method(PUBLIC, "fine", "(I)V") { parameters = listOf("ok") }
        }

        val read = extractRead(bytes, "p/A", includeGenericSignatures = true)

        assertNull(read.member("named").parameterNames)
        assertNull(read.member("generic").genericSignature)
        assertEquals(listOf("ok"), read.member("fine").parameterNames)
        assertEquals(3, read.members().size)
        assertEquals(1, read.droppedParameterNames)
        assertEquals(1, read.droppedSignatures)
        assertEquals(0, read.droppedMembers)
    }

    @Test
    fun `clears an outer class name that cannot be represented and keeps the types`() {
        val bytes = classFile("p/A\$B") {
            innerClass("p/A\$B", "p/A$LONE_HIGH", "B", Opcodes.ACC_STATIC)
            method(PUBLIC, "run", "()V")
        }

        val read = extractRead(bytes, "p/A\$B")

        assertNull(read.fact.outerClass)
        assertEquals("B", read.fact.simpleName)
        assertTrue(read.outerClassCleared)
        assertFalse(read.typesOmitted)
        assertEquals(listOf("run"), read.members().map { it.name })
    }

    @Test
    fun `falls back to the last segment of the name when the inner name cannot be represented`() {
        val bytes = classFile("p/A\$B") { innerClass("p/A\$B", "p/A", "B$LONE_LOW", Opcodes.ACC_STATIC) }

        assertEquals("A\$B", extractRead(bytes, "p/A\$B").fact.simpleName)
    }

    @Test
    fun `keeps NUL and supplementary characters through write and read`() {
        val className = "p/Smile\uD83D\uDE00"
        val bytes = classFile(className) { method(PUBLIC, "a\u0000b", "()V") }

        val read = extractRead(bytes, className)
        val facts = factsOf(read, bytes)
        val file = assertIs<LoadResult.Loaded<*>>(FactsFile.open(FactsWriter.write(facts))).value
        val loaded = assertIs<LoadResult.Loaded<*>>(assertIs<FactsFile>(file).loadAll()).value

        assertEquals(facts, loaded)
        assertEquals(className, read.fact.internalName)
        assertEquals("a\u0000b", read.member("a\u0000b").name)
    }

    @Test
    fun `drops a member name that ASM decodes past the end of its constant`() {
        // A 65,535-byte constant ending in a lead byte: ASM reads the next bytes to finish the character, so the
        // string it returns needs more than the 65,535 bytes the facts format allows.
        for (lead in listOf(OVERRUN_LEAD_3, OVERRUN_LEAD_2)) {
            val bytes = overrunning(lead) { name -> method(PUBLIC, name, "()V") }

            val read = extractRead(bytes, "p/A")

            assertEquals(0, read.members().size, "lead 0x${lead.toString(16)}")
            assertEquals(1, read.droppedMembers)
            FactsWriter.write(factsOf(read, bytes))
        }
    }

    @Test
    fun `drops a signature and parameter names that ASM decodes past the end of their constants`() {
        val signature = overrunning(OVERRUN_LEAD_3) { name -> method(PUBLIC, "run", "()V", name) }
        val parameter = overrunning(OVERRUN_LEAD_2) { name ->
            method(PUBLIC, "run", "(I)V") { parameters = listOf(name) }
        }

        val withSignature = extractRead(signature, "p/A", includeGenericSignatures = true)
        val withParameter = extractRead(parameter, "p/A")

        assertNull(withSignature.member("run").genericSignature)
        assertEquals(1, withSignature.droppedSignatures)
        assertNull(withParameter.member("run").parameterNames)
        assertEquals(1, withParameter.droppedParameterNames)
        FactsWriter.write(factsOf(withSignature, signature))
        FactsWriter.write(factsOf(withParameter, parameter))
    }

    @Test
    fun `omits types and clears names that ASM decodes past the end of their constants`() {
        val supertype = overrunning(OVERRUN_LEAD_3) { name -> superName = name }
        val outer = overrunning(OVERRUN_LEAD_2, "p/A\$B") { name -> innerClass("p/A\$B", name, "B", 0) }
        val inner = overrunning(OVERRUN_LEAD_3, "p/A\$B") { name -> innerClass("p/A\$B", "p/A", name, 0) }

        val superRead = extractRead(supertype, "p/A")
        val outerRead = extractRead(outer, "p/A\$B")
        val innerRead = extractRead(inner, "p/A\$B")

        assertNull(superRead.types)
        assertTrue(superRead.typesOmitted)
        assertNull(outerRead.fact.outerClass)
        assertTrue(outerRead.outerClassCleared)
        assertEquals("A\$B", innerRead.fact.simpleName)
        for ((read, bytes) in listOf(superRead to supertype, outerRead to outer, innerRead to inner)) {
            FactsWriter.write(factsOf(read, bytes))
        }
    }
}

private const val OVERRUN_LEAD_3 = 0xE4
private const val OVERRUN_LEAD_2 = 0xC2
private const val MAX_UTF8 = 65_535

/**
 * A class [name] built by [configure], which is given a name of [MAX_UTF8] ASCII letters; the last byte of that
 * constant is then replaced by [lead], the first byte of a character that continues past the constant.
 */
private fun overrunning(lead: Int, name: String = "p/A", configure: ClassBuilder.(String) -> Unit): ByteArray {
    val letters = "a".repeat(MAX_UTF8)
    val bytes = classFile(name) { configure(letters) }
    val run = letters.toByteArray(Charsets.US_ASCII)
    val start = (0..bytes.size - run.size).first { index -> matchesAt(bytes, run, index) }
    return bytes.copyOf().also { patched -> patched[start + run.size - 1] = lead.toByte() }
}

private fun matchesAt(bytes: ByteArray, run: ByteArray, index: Int): Boolean =
    run.indices.all { offset -> bytes[index + offset] == run[offset] }

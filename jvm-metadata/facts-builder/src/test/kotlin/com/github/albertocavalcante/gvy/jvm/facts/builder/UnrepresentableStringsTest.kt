// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import com.github.albertocavalcante.gvy.jvm.facts.ExtensionsSection
import com.github.albertocavalcante.gvy.jvm.facts.Facts
import com.github.albertocavalcante.gvy.jvm.facts.FactsFile
import com.github.albertocavalcante.gvy.jvm.facts.FactsHeader
import com.github.albertocavalcante.gvy.jvm.facts.FactsWriter
import com.github.albertocavalcante.gvy.jvm.facts.InputKind
import com.github.albertocavalcante.gvy.jvm.facts.LoadResult
import com.github.albertocavalcante.gvy.jvm.facts.NamesSection
import com.github.albertocavalcante.gvy.jvm.facts.PackageFact
import com.github.albertocavalcante.gvy.jvm.facts.Sha256
import com.github.albertocavalcante.gvy.jvm.facts.TypesSection
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import java.security.MessageDigest
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
        assertTrue(read.typesOmitted)
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
        val digest = Sha256.of(MessageDigest.getInstance("SHA-256").digest(bytes))
        val facts = Facts(
            header = FactsHeader("test", InputKind.JAR, null, digest, bytes.size.toLong()),
            names = NamesSection(listOf(PackageFact("p", null)), listOf(read.fact)),
            types = TypesSection(listOfNotNull(read.types)),
            extensions = ExtensionsSection(emptyList()),
        )
        val file = assertIs<LoadResult.Loaded<*>>(FactsFile.open(FactsWriter.write(facts))).value
        val loaded = assertIs<LoadResult.Loaded<*>>(assertIs<FactsFile>(file).loadAll()).value

        assertEquals(facts, loaded)
        assertEquals(className, read.fact.internalName)
        assertEquals("a\u0000b", read.member("a\u0000b").name)
    }
}

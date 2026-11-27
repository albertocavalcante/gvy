// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import com.github.albertocavalcante.gvy.jvm.facts.FactsFormat
import com.github.albertocavalcante.gvy.jvm.facts.MemberKind
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val PUBLIC = Opcodes.ACC_PUBLIC
private const val PROTECTED = Opcodes.ACC_PROTECTED

class MemberSelectionTest {
    @Test
    fun `keeps every class but only public and protected members`() {
        val bytes = classFile("p/Hidden", Opcodes.ACC_SUPER) {
            field(PUBLIC, "pub", "I")
            field(0, "pkg", "I")
            field(Opcodes.ACC_PRIVATE, "secret", "I")
            method(PROTECTED, "prot", "()V")
            method(Opcodes.ACC_PRIVATE, "priv", "()V")
            method(Opcodes.ACC_STATIC, "<clinit>", "()V")
        }

        val read = extractRead(bytes, "p/Hidden")

        assertEquals("p/Hidden", read.fact.internalName)
        assertEquals(listOf("prot", "pub"), read.members().map { it.name })
        assertEquals(0, read.droppedMembers)
    }

    @Test
    fun `includes synthetic and bridge members with their flags`() {
        val bridge = PUBLIC or Opcodes.ACC_BRIDGE or Opcodes.ACC_SYNTHETIC
        val bytes = classFile("p/A") {
            method(bridge, "compareTo", "(Ljava/lang/Object;)I")
            method(PUBLIC, "compareTo", "(Lp/A;)I")
            field(PUBLIC or Opcodes.ACC_SYNTHETIC, "cache", "I")
        }

        val read = extractRead(bytes, "p/A")

        assertEquals(0x1041, read.members().single { it.descriptor == "(Ljava/lang/Object;)I" }.accessFlags)
        assertEquals(0x1, read.members().single { it.descriptor == "(Lp/A;)I" }.accessFlags)
        assertEquals(0x1001, read.member("cache").accessFlags)
    }

    @Test
    fun `marks members deprecated from the attribute and from the annotation`() {
        val bytes = classFile("p/A") {
            field(PUBLIC, "byAttribute", "I", deprecation = Deprecation.ATTRIBUTE)
            field(PUBLIC, "byAnnotation", "I", deprecation = Deprecation.ANNOTATION)
            method(PUBLIC, "plain", "()V")
            method(PROTECTED, "old", "()V") { deprecation = Deprecation.ATTRIBUTE }
            method(PUBLIC, "older", "()V") { deprecation = Deprecation.ANNOTATION }
        }

        val read = extractRead(bytes, "p/A")

        assertEquals(FactsFormat.DEPRECATED_FLAG or PUBLIC, read.member("byAttribute").accessFlags)
        assertEquals(FactsFormat.DEPRECATED_FLAG or PUBLIC, read.member("byAnnotation").accessFlags)
        assertEquals(PUBLIC, read.member("plain").accessFlags)
        assertEquals(FactsFormat.DEPRECATED_FLAG or PROTECTED, read.member("old").accessFlags)
        assertEquals(FactsFormat.DEPRECATED_FLAG or PUBLIC, read.member("older").accessFlags)
    }

    @Test
    fun `drops duplicate members with the same name and descriptor across fields and methods`() {
        val bytes = classFile("p/A") {
            field(PUBLIC, "x", "I")
            field(PROTECTED, "x", "I")
            // A field descriptor is never a method descriptor, so this field does not collide with the method below;
            // it is dropped as malformed. The writer would refuse a model where both were kept.
            field(PUBLIC, "m", "()V")
            method(PUBLIC, "m", "()V")
            method(PROTECTED, "m", "()V")
            method(PUBLIC, "m", "(I)V")
        }

        val read = extractRead(bytes, "p/A")

        val kept = read.members().map { Triple(it.kind, it.name + it.descriptor, it.accessFlags) }
        assertEquals(
            listOf(
                Triple(MemberKind.METHOD, "m()V", PUBLIC),
                Triple(MemberKind.METHOD, "m(I)V", PUBLIC),
                Triple(MemberKind.FIELD, "xI", PUBLIC),
            ),
            kept,
        )
        assertEquals(3, read.droppedMembers)
    }

    @Test
    fun `drops a member with a malformed descriptor`() {
        val badFields = listOf("Lp/A", "", "V", "[", "II", "Lp.A;", "L;")
        val badMethods = listOf("(I", "()", "(V)V", "I", "(I)VV", "(Lp/A)V", "(", ")V")
        val bytes = classFile("p/A") {
            field(PUBLIC, "ok", "I")
            field(PUBLIC, "array", "[[Ljava/lang/String;")
            method(PUBLIC, "run", "(IJ[Ljava/lang/String;)V")
            method(PUBLIC, "get", "()Lp/A;")
            badFields.forEachIndexed { index, descriptor -> field(PUBLIC, "badField$index", descriptor) }
            badMethods.forEachIndexed { index, descriptor -> method(PUBLIC, "badMethod$index", descriptor) }
        }

        val read = extractRead(bytes, "p/A")

        assertEquals(listOf("array", "get", "ok", "run"), read.members().map { it.name })
        assertEquals(badFields.size + badMethods.size, read.droppedMembers)
    }

    @Test
    fun `keeps generic signatures only when asked`() {
        val fieldSignature = "Ljava/util/List<Ljava/lang/String;>;"
        val methodSignature = "<T:Ljava/lang/Object;>(TT;)TT;"
        val bytes = classFile("p/A") {
            field(PUBLIC, "items", "Ljava/util/List;", fieldSignature)
            method(PUBLIC, "id", "(Ljava/lang/Object;)Ljava/lang/Object;", methodSignature)
            method(PUBLIC, "plain", "()V")
        }

        val without = extractRead(bytes, "p/A", includeGenericSignatures = false)
        val with = extractRead(bytes, "p/A", includeGenericSignatures = true)

        assertNull(without.member("items").genericSignature)
        assertNull(without.member("id").genericSignature)
        assertEquals(fieldSignature, with.member("items").genericSignature)
        assertEquals(methodSignature, with.member("id").genericSignature)
        assertNull(with.member("plain").genericSignature)
        assertNotNull(with.types)
        assertEquals(0, with.droppedSignatures)
    }
}

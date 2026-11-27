// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import com.github.albertocavalcante.gvy.jvm.facts.ExtensionsSection
import com.github.albertocavalcante.gvy.jvm.facts.Facts
import com.github.albertocavalcante.gvy.jvm.facts.FactsHeader
import com.github.albertocavalcante.gvy.jvm.facts.InputKind
import com.github.albertocavalcante.gvy.jvm.facts.MemberFact
import com.github.albertocavalcante.gvy.jvm.facts.NamesSection
import com.github.albertocavalcante.gvy.jvm.facts.PackageFact
import com.github.albertocavalcante.gvy.jvm.facts.Sha256
import com.github.albertocavalcante.gvy.jvm.facts.TypesSection
import org.objectweb.asm.Attribute
import org.objectweb.asm.ByteVector
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.security.MessageDigest
import kotlin.test.assertIs

internal const val PUBLIC_SUPER = Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER
internal const val DEPRECATED_ANNOTATION = "Ljava/lang/Deprecated;"

/** How a class, field or method is marked deprecated in a fixture. */
internal enum class Deprecation { NONE, ATTRIBUTE, ANNOTATION }

/**
 * An attribute whose bytes the fixture writes itself, for shapes that `ClassWriter` cannot produce. [content] may add
 * constant-pool entries through the writer it is given.
 */
internal class RawAttribute(type: String, private val content: (ClassWriter) -> ByteVector) : Attribute(type) {
    override fun write(
        classWriter: ClassWriter,
        code: ByteArray?,
        codeLength: Int,
        maxStack: Int,
        maxLocals: Int,
    ): ByteVector = content(classWriter)
}

/** What a fixture method carries besides its flags, name and descriptor. */
internal class MethodOptions {
    /** One `MethodParameters` record per entry; a null name is an unnamed record. Null writes no attribute. */
    var parameters: List<String?>? = null

    /** Writes a `Code` attribute whose `LocalVariableTable` holds these names. */
    var localVariables: List<String>? = null
    var deprecation: Deprecation = Deprecation.NONE
    val rawAttributes = ArrayList<RawAttribute>()
}

/** Builds the bytes of a class file with ASM's `ClassWriter`, for the fixtures of the tests. */
internal class ClassBuilder {
    var superName: String? = "java/lang/Object"
    var interfaces: List<String> = emptyList()
    var version: Int = Opcodes.V17
    var deprecation: Deprecation = Deprecation.NONE
    private val steps = ArrayList<(ClassWriter) -> Unit>()

    fun annotation(descriptor: String) {
        steps.add { writer -> writer.visitAnnotation(descriptor, true).visitEnd() }
    }

    fun rawAttribute(type: String, content: (ClassWriter) -> ByteVector) {
        steps.add { writer -> writer.visitAttribute(RawAttribute(type, content)) }
    }

    fun enclosingMethod(owner: String) {
        steps.add { writer -> writer.visitOuterClass(owner, null, null) }
    }

    fun innerClass(name: String, outer: String?, inner: String?, access: Int) {
        steps.add { writer -> writer.visitInnerClass(name, outer, inner, access) }
    }

    fun recordComponent(name: String, descriptor: String) {
        steps.add { writer -> writer.visitRecordComponent(name, descriptor, null).visitEnd() }
    }

    fun field(
        access: Int,
        name: String,
        descriptor: String,
        signature: String? = null,
        deprecation: Deprecation = Deprecation.NONE,
    ) {
        steps.add { writer ->
            val field = writer.visitField(withAttribute(access, deprecation), name, descriptor, signature, null)
            if (deprecation == Deprecation.ANNOTATION) field.visitAnnotation(DEPRECATED_ANNOTATION, true).visitEnd()
            field.visitEnd()
        }
    }

    fun method(
        access: Int,
        name: String,
        descriptor: String,
        signature: String? = null,
        configure: MethodOptions.() -> Unit = {},
    ) {
        val options = MethodOptions().apply(configure)
        steps.add { writer ->
            val flags = withAttribute(access, options.deprecation)
            val method = writer.visitMethod(flags, name, descriptor, signature, null)
            options.parameters?.forEach { parameter -> method.visitParameter(parameter, 0) }
            if (options.deprecation == Deprecation.ANNOTATION) {
                method.visitAnnotation(DEPRECATED_ANNOTATION, true).visitEnd()
            }
            options.rawAttributes.forEach { attribute -> method.visitAttribute(attribute) }
            options.localVariables?.let { names -> writeLocalVariables(method, names) }
            method.visitEnd()
        }
    }

    fun build(name: String, access: Int): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(version, withAttribute(access, deprecation), name, null, superName, interfaces.toTypedArray())
        writer.visitSource("Fixture.java", null)
        steps.forEach { step -> step(writer) }
        if (deprecation == Deprecation.ANNOTATION) writer.visitAnnotation(DEPRECATED_ANNOTATION, true).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** ASM writes the `Deprecated` attribute when the pseudo flag is set. */
    private fun withAttribute(access: Int, deprecation: Deprecation): Int =
        if (deprecation == Deprecation.ATTRIBUTE) access or Opcodes.ACC_DEPRECATED else access

    private fun writeLocalVariables(method: MethodVisitor, names: List<String>) {
        val start = Label()
        val end = Label()
        method.visitCode()
        method.visitLabel(start)
        method.visitInsn(Opcodes.RETURN)
        method.visitLabel(end)
        names.forEachIndexed { index, local -> method.visitLocalVariable(local, "I", null, start, end, index) }
        method.visitMaxs(0, names.size)
    }
}

/** The bytes of class [name]; [init] adds members, attributes and the other settings of the class. */
internal fun classFile(name: String, access: Int = PUBLIC_SUPER, init: ClassBuilder.() -> Unit = {}): ByteArray =
    ClassBuilder().apply(init).build(name, access)

/** Extracts [bytes] as the class [name] and expects a class to come out. */
internal fun extractRead(
    bytes: ByteArray,
    name: String,
    includeGenericSignatures: Boolean = false,
    release: Int = 0,
): Extraction.Read = assertIs<Extraction.Read>(
    ClassFileExtractor.extract(name, release, bytes, includeGenericSignatures),
)

internal fun extractResult(bytes: ByteArray, name: String): Extraction =
    ClassFileExtractor.extract(name, 0, bytes, includeGenericSignatures = false)

/** The members of a class that kept its types, in the order of the model: by name, then descriptor. */
internal fun Extraction.Read.members(): List<MemberFact> =
    checkNotNull(types) { "Expected the types to be kept" }.members

internal fun Extraction.Read.member(name: String): MemberFact = members().single { it.name == name }

/** A minimal model around [read], so a test can hand what the extractor produced to the real writer. */
internal fun factsOf(read: Extraction.Read, bytes: ByteArray): Facts {
    val digest = Sha256.of(MessageDigest.getInstance("SHA-256").digest(bytes))
    return Facts(
        header = FactsHeader("test", InputKind.JAR, null, digest, bytes.size.toLong()),
        names = NamesSection(listOf(PackageFact(read.fact.packageName, null)), listOf(read.fact)),
        types = TypesSection(listOfNotNull(read.types)),
        extensions = ExtensionsSection(emptyList()),
    )
}

/**
 * A `RuntimeVisibleAnnotations` attribute with one annotation whose value is an array nested [depth] levels deep.
 * ASM skips such a value recursively, even when no visitor wants it.
 */
internal fun nestedAnnotationAttribute(depth: Int): (ClassWriter) -> ByteVector = { writer ->
    val bytes = ByteVector()
        .putShort(1)
        .putShort(writer.newUTF8("Lp/Ann;"))
        .putShort(1)
        .putShort(writer.newUTF8("v"))
    repeat(depth) { bytes.putByte('['.code).putShort(1) }
    bytes.putByte('['.code).putShort(0)
}

// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import com.github.albertocavalcante.gvy.jvm.facts.ClassFact
import com.github.albertocavalcante.gvy.jvm.facts.ClassTypes
import com.github.albertocavalcante.gvy.jvm.facts.FactsFormat
import com.github.albertocavalcante.gvy.jvm.facts.MemberFact
import com.github.albertocavalcante.gvy.jvm.facts.MemberKind
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import java.nio.ByteBuffer

/** The outcome of reading one class file. */
internal sealed interface Extraction {
    /**
     * A class that was read. [types] is null when a supertype name cannot be represented. [droppedMembers] counts
     * public or protected members left out; [droppedParameterNames] counts methods whose names were dropped because
     * one cannot be represented; [droppedSignatures] counts generic signatures that cannot be represented.
     * [typesOmitted] is true when [types] was left out or the outer class was cleared for that reason.
     * [visibilityDisagreement] is true for a nested class whose own flags and `InnerClasses` flags disagree on
     * whether the class is visible outside its package.
     */
    data class Read(
        val fact: ClassFact,
        val types: ClassTypes?,
        val droppedMembers: Int,
        val droppedParameterNames: Int,
        val droppedSignatures: Int,
        val typesOmitted: Boolean,
        val visibilityDisagreement: Boolean,
    ) : Extraction

    /** A class file the builder does not keep. */
    data class Skipped(val reason: SkipReason) : Extraction
}

private const val MAGIC_NUMBER = -889275714 // 0xCAFEBABE as a signed int
private const val MAJOR_OFFSET = 6
private const val MIN_CLASS_FILE_BYTES = 10
private const val ACCESS_MASK = 0xFFFF
private const val MAX_MAJOR = 0xFFFF

/** Turns the bytes of one class file into facts. Never throws for malformed bytes. */
internal object ClassFileExtractor {
    /**
     * Reads [bytes], which must be the class file found at the path of the class [expectedName] in copy [release].
     * Members other than public and protected ones are not kept; generic signatures are kept only when
     * [includeGenericSignatures] is set.
     */
    fun extract(expectedName: String, release: Int, bytes: ByteArray, includeGenericSignatures: Boolean): Extraction {
        if (!hasClassHeader(bytes)) return Extraction.Skipped(SkipReason.MALFORMED)
        if (majorVersion(bytes) > NewestReadableMajor.value) {
            return Extraction.Skipped(SkipReason.UNSUPPORTED_CLASS_VERSION)
        }
        val collector = ClassCollector()
        val failure = parseInto(collector, bytes)
        return when {
            failure != null -> Extraction.Skipped(failure)
            collector.name != expectedName -> Extraction.Skipped(SkipReason.NAME_MISMATCH)
            else -> assemble(collector, release, includeGenericSignatures)
        }
    }
}

/** ASM does not check the magic number, so it is checked here. */
private fun hasClassHeader(bytes: ByteArray): Boolean =
    bytes.size >= MIN_CLASS_FILE_BYTES && ByteBuffer.wrap(bytes).getInt(0) == MAGIC_NUMBER

/** The major version as an unsigned 16-bit value; ASM reads it as a signed one, so it is not used for this check. */
private fun majorVersion(bytes: ByteArray): Int = ByteBuffer.wrap(bytes).getShort(MAJOR_OFFSET).toInt() and MAX_MAJOR

/**
 * Parses [bytes] into [collector] and returns why that failed, or null. `SKIP_DEBUG` is not passed: ASM reads the
 * `MethodParameters` attribute only without it. `SKIP_CODE` already skips code and its local variable tables.
 */
@Suppress("TooGenericExceptionCaught") // ASM reports damaged input as assorted runtime exceptions
private fun parseInto(collector: ClassCollector, bytes: ByteArray): SkipReason? = try {
    ClassReader(bytes).accept(collector, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
    null
} catch (_: RuntimeException) {
    SkipReason.MALFORMED
}

/**
 * The newest class-file major version the ASM on the class path reads, found by probing it, so that the code does not
 * depend on the ASM version: Gradle and Bazel can resolve different ones.
 */
private object NewestReadableMajor {
    val value: Int by lazy { probe() }

    private fun probe(): Int {
        var major = Opcodes.V17
        while (major < MAX_MAJOR && reads(major + 1)) major++
        return major
    }

    /** Whether ASM accepts a minimal header (magic, minor 0, [major], an empty constant pool). */
    private fun reads(major: Int): Boolean {
        val header = ByteBuffer.allocate(MIN_CLASS_FILE_BYTES)
            .putInt(MAGIC_NUMBER)
            .putShort(0.toShort())
            .putShort(major.toShort())
            .putShort(1.toShort())
            .array()
        return try {
            ClassReader(header)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}

private fun assemble(collector: ClassCollector, release: Int, includeGenericSignatures: Boolean): Extraction.Read {
    val name = collector.name
    val self = collector.selfEntry
    val outer = outerClassOf(collector)
    val outerRepresentable = outer == null || outer.isWellFormedUtf16()
    val members = MemberSelector(includeGenericSignatures)
    collector.members.forEach { member -> members.add(member) }
    val fact = ClassFact(
        internalName = name,
        packageName = name.substringBeforeLast('/', "").replace('/', '.'),
        simpleName = simpleNameOf(name, self),
        outerClass = if (outerRepresentable) outer else null,
        accessFlags = classFlags(collector),
        release = release,
    )
    val supertypes = listOfNotNull(collector.superName) + collector.interfaces
    val typesRepresentable = supertypes.all { it.isWellFormedUtf16() }
    val types = if (typesRepresentable) {
        ClassTypes(name, release, collector.superName, collector.interfaces, members.sortedMembers())
    } else {
        null
    }
    return Extraction.Read(
        fact = fact,
        types = types,
        droppedMembers = members.droppedMembers,
        droppedParameterNames = members.droppedParameterNames,
        droppedSignatures = members.droppedSignatures,
        typesOmitted = !typesRepresentable || !outerRepresentable,
        visibilityDisagreement = self != null && ownVisible(collector.access) != innerVisible(self.access),
    )
}

/**
 * The flags of the class's own `InnerClasses` entry for itself, which is what `Class.getModifiers` reports, or the
 * class's own flags when it has none; plus the deprecation bit. ASM adds pseudo flags above 16 bits (deprecated,
 * record) that clash with the deprecation bit of the format, so the access is masked first.
 */
private fun classFlags(collector: ClassCollector): Int {
    val access = collector.selfEntry?.access ?: collector.access
    val deprecated = collector.access.hasFlag(Opcodes.ACC_DEPRECATED) || collector.deprecatedAnnotation
    return withDeprecation(access, deprecated)
}

private fun withDeprecation(access: Int, deprecated: Boolean): Int {
    val flags = access and ACCESS_MASK
    return if (deprecated) flags or FactsFormat.DEPRECATED_FLAG else flags
}

/** The member class's declaring class, or for a local or anonymous class the owner its `EnclosingMethod` names. */
private fun outerClassOf(collector: ClassCollector): String? {
    val self = collector.selfEntry ?: return null
    return self.outerName ?: collector.enclosingOwner
}

/** The `inner_name` of the self entry (empty when anonymous), or the part of the name after the last slash. */
private fun simpleNameOf(internalName: String, self: InnerEntry?): String {
    val fallback = internalName.substringAfterLast('/')
    val innerName = self?.simpleName
    return when {
        self == null -> fallback
        innerName == null -> ""
        innerName.isWellFormedUtf16() -> innerName
        else -> fallback
    }
}

private fun ownVisible(access: Int): Boolean = access.hasFlag(Opcodes.ACC_PUBLIC) &&
    !access.hasFlag(Opcodes.ACC_SYNTHETIC) &&
    !access.hasFlag(Opcodes.ACC_MODULE)

private fun innerVisible(access: Int): Boolean =
    access.hasFlag(Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) && !access.hasFlag(Opcodes.ACC_SYNTHETIC)

/** Selects, checks and deduplicates the members of one class and counts what it leaves out. */
private class MemberSelector(private val includeGenericSignatures: Boolean) {
    private val seen = HashSet<Pair<String, String>>()
    private val members = ArrayList<MemberFact>()

    var droppedMembers = 0
        private set
    var droppedParameterNames = 0
        private set
    var droppedSignatures = 0
        private set

    fun add(member: RawMember) {
        val parameterCount = parameterCountOrNull(member)
        if (parameterCount == null || !hasRepresentableText(member) || !seen.add(member.name to member.descriptor)) {
            droppedMembers++
            return
        }
        members.add(
            MemberFact(
                kind = member.kind,
                accessFlags = withDeprecation(member.access, member.deprecated),
                name = member.name,
                descriptor = member.descriptor,
                parameterNames = parameterNamesOf(member, parameterCount),
                genericSignature = signatureOf(member),
            ),
        )
    }

    fun sortedMembers(): List<MemberFact> = members.sortedWith(compareBy({ it.name }, { it.descriptor }))

    private fun signatureOf(member: RawMember): String? {
        val signature = member.signature
        return when {
            !includeGenericSignatures || signature == null -> null
            signature.isWellFormedUtf16() -> signature
            else -> {
                droppedSignatures++
                null
            }
        }
    }

    /**
     * Names come from `MethodParameters` only. They are absent when the attribute is missing or its count differs
     * from the descriptor's, and a method without parameters has an empty list.
     */
    private fun parameterNamesOf(member: RawMember, parameterCount: Int): List<String>? = when {
        member.kind == MemberKind.FIELD -> null
        member.parameterNames.size != parameterCount -> null
        member.parameterNames.any { name -> name != null && !name.isWellFormedUtf16() } -> {
            droppedParameterNames++
            null
        }
        else -> member.parameterNames.map { name -> name ?: "" }
    }
}

private fun hasRepresentableText(member: RawMember): Boolean =
    member.name.isWellFormedUtf16() && member.descriptor.isWellFormedUtf16()

/** The parameter count of a method, 0 for a field, or null when the descriptor is not valid for the kind. */
private fun parameterCountOrNull(member: RawMember): Int? = when (member.kind) {
    MemberKind.FIELD -> if (Descriptors.isFieldDescriptor(member.descriptor)) 0 else null
    MemberKind.METHOD -> Descriptors.parameterCount(member.descriptor)
}

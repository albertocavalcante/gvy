// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import com.github.albertocavalcante.gvy.jvm.facts.MemberKind
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

private const val DEPRECATED_DESCRIPTOR = "Ljava/lang/Deprecated;"

/** Whether any bit of [flag] is set. */
internal fun Int.hasFlag(flag: Int): Boolean = (this and flag) != 0

/** The class's own `InnerClasses` entry for itself. [simpleName] is null for an anonymous class. */
internal class InnerEntry(val outerName: String?, val simpleName: String?, val access: Int)

/**
 * A public or protected member as the class file declares it. [parameterNames] holds one entry per
 * `MethodParameters` record, null for a record without a name; it stays empty without the attribute.
 */
internal class RawMember(
    val kind: MemberKind,
    val access: Int,
    val name: String,
    val descriptor: String,
    val signature: String?,
) {
    var deprecatedAnnotation = false
    val parameterNames = ArrayList<String?>()

    /** Deprecated by the `Deprecated` attribute (ASM reports it as a pseudo flag) or by the annotation. */
    val deprecated: Boolean
        get() = access.hasFlag(Opcodes.ACC_DEPRECATED) || deprecatedAnnotation
}

/** Collects what the builder needs from one class; every callback only records. */
internal class ClassCollector : ClassVisitor(Opcodes.ASM9) {
    var name: String = ""
        private set
    var access: Int = 0
        private set
    var superName: String? = null
        private set
    var interfaces: List<String> = emptyList()
        private set
    var enclosingOwner: String? = null
        private set
    var selfEntry: InnerEntry? = null
        private set
    var deprecatedAnnotation = false
        private set
    val members = ArrayList<RawMember>()

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        this.access = access
        this.name = name
        this.superName = superName
        this.interfaces = interfaces?.toList().orEmpty()
    }

    override fun visitOuterClass(owner: String, name: String?, descriptor: String?) {
        enclosingOwner = owner
    }

    override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
        if (name == this.name && selfEntry == null) selfEntry = InnerEntry(outerName, innerName, access)
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        if (descriptor == DEPRECATED_DESCRIPTOR) deprecatedAnnotation = true
        return null
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?,
    ): FieldVisitor? {
        if (!isPublicOrProtected(access)) return null
        val member = RawMember(MemberKind.FIELD, access, name, descriptor, signature)
        members.add(member)
        return DeprecationFieldVisitor(member)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        if (!isPublicOrProtected(access)) return null
        val member = RawMember(MemberKind.METHOD, access, name, descriptor, signature)
        members.add(member)
        return ParameterMethodVisitor(member)
    }

    private fun isPublicOrProtected(access: Int): Boolean =
        access.hasFlag(Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED)
}

private class DeprecationFieldVisitor(private val member: RawMember) : FieldVisitor(Opcodes.ASM9) {
    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        if (descriptor == DEPRECATED_DESCRIPTOR) member.deprecatedAnnotation = true
        return null
    }
}

private class ParameterMethodVisitor(private val member: RawMember) : MethodVisitor(Opcodes.ASM9) {
    override fun visitParameter(name: String?, access: Int) {
        member.parameterNames.add(name)
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        if (descriptor == DEPRECATED_DESCRIPTOR) member.deprecatedAnnotation = true
        return null
    }
}

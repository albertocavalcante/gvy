// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

/** The smallest encoded size of each repeated item, used to check a count against the remaining bytes. */
internal const val MIN_PACKAGE_BYTES = 2
internal const val MIN_CLASS_BYTES = 6
internal const val MIN_TYPES_CLASS_BYTES = 5
internal const val MIN_MEMBER_BYTES = 6
internal const val MIN_EXTENSION_BYTES = 5

/** Every string the sections refer to, for the pool. */
internal fun collectStrings(facts: Facts): Set<String> {
    val strings = HashSet<String>()
    for (fact in facts.names.packages) {
        strings += fact.name
        fact.module?.let(strings::add)
    }
    for (fact in facts.names.classes) {
        strings += fact.internalName
        strings += fact.simpleName
        fact.outerClass?.let(strings::add)
    }
    for (fact in facts.types.classes) {
        strings += fact.internalName
        fact.superName?.let(strings::add)
        strings.addAll(fact.interfaces)
        for (member in fact.members) {
            strings += member.name
            strings += member.descriptor
            member.parameterNames?.let { names -> strings.addAll(names) }
            member.genericSignature?.let(strings::add)
        }
    }
    for (fact in facts.extensions.entries) {
        strings += fact.holderClass
        strings += fact.moduleName
        strings += fact.moduleVersion
    }
    return strings
}

private fun requireDistinct(keys: List<Any>, what: String) {
    require(keys.toSet().size == keys.size) { "Duplicate $what" }
}

internal fun encodeNames(names: NamesSection, pool: StringPoolWriter): ByteArray {
    val packages = names.packages.sortedBy { fact -> pool.indexOf(fact.name) }
    requireDistinct(packages.map { fact -> fact.name }, "package name")
    val packageIndices = packages.withIndex().associate { (index, fact) -> fact.name to index }
    val classes = names.classes.sortedWith(
        compareBy<ClassFact>({ fact -> pool.indexOf(fact.internalName) }, { fact -> fact.release }),
    )
    requireDistinct(classes.map { fact -> fact.internalName to fact.release }, "class and release")

    val sink = ByteSink()
    sink.writeVarint(packages.size)
    for (fact in packages) {
        sink.writeVarint(pool.indexOf(fact.name))
        sink.writeOptionalIndex(fact.module?.let(pool::indexOf))
    }
    sink.writeVarint(classes.size)
    for (fact in classes) {
        val packageIndex = requireNotNull(packageIndices[fact.packageName]) {
            "A class refers to a package that the names section does not list"
        }
        sink.writeVarint(pool.indexOf(fact.internalName))
        sink.writeVarint(packageIndex)
        sink.writeVarint(pool.indexOf(fact.simpleName))
        sink.writeOptionalIndex(fact.outerClass?.let(pool::indexOf))
        sink.writeVarint(fact.accessFlags)
        sink.writeVarint(fact.release)
    }
    return sink.toByteArray()
}

internal fun encodeTypes(types: TypesSection, pool: StringPoolWriter): ByteArray {
    val classes = types.classes.sortedWith(
        compareBy<ClassTypes>({ fact -> pool.indexOf(fact.internalName) }, { fact -> fact.release }),
    )
    requireDistinct(classes.map { fact -> fact.internalName to fact.release }, "class and release")

    val sink = ByteSink()
    sink.writeVarint(classes.size)
    for (fact in classes) {
        sink.writeVarint(pool.indexOf(fact.internalName))
        sink.writeVarint(fact.release)
        sink.writeOptionalIndex(fact.superName?.let(pool::indexOf))
        sink.writeVarint(fact.interfaces.size)
        for (name in fact.interfaces) sink.writeVarint(pool.indexOf(name))
        val members = fact.members.sortedWith(
            compareBy<MemberFact>(
                { member -> pool.indexOf(member.name) },
                { member -> pool.indexOf(member.descriptor) },
            ),
        )
        requireDistinct(members.map { member -> member.name to member.descriptor }, "member name and descriptor")
        sink.writeVarint(members.size)
        for (member in members) encodeMember(sink, member, pool)
    }
    return sink.toByteArray()
}

private fun encodeMember(sink: ByteSink, member: MemberFact, pool: StringPoolWriter) {
    sink.writeByte(member.kind.code)
    sink.writeVarint(member.accessFlags)
    sink.writeVarint(pool.indexOf(member.name))
    sink.writeVarint(pool.indexOf(member.descriptor))
    val parameterNames = member.parameterNames
    if (parameterNames == null) {
        sink.writeVarint(0)
    } else {
        sink.writeVarint(parameterNames.size + 1)
        for (name in parameterNames) sink.writeVarint(pool.indexOf(name))
    }
    sink.writeOptionalIndex(member.genericSignature?.let(pool::indexOf))
}

internal fun encodeExtensions(extensions: ExtensionsSection, pool: StringPoolWriter): ByteArray {
    val entries = extensions.entries.sortedWith(
        compareBy<ExtensionFact>(
            { fact -> pool.indexOf(fact.holderClass) },
            { fact -> fact.kind.code },
            { fact -> fact.source.code },
            { fact -> pool.indexOf(fact.moduleName) },
            { fact -> pool.indexOf(fact.moduleVersion) },
        ),
    )
    val sink = ByteSink()
    sink.writeVarint(entries.size)
    for (fact in entries) {
        sink.writeVarint(pool.indexOf(fact.holderClass))
        sink.writeByte(fact.kind.code)
        sink.writeByte(fact.source.code)
        sink.writeVarint(pool.indexOf(fact.moduleName))
        sink.writeVarint(pool.indexOf(fact.moduleVersion))
    }
    return sink.toByteArray()
}

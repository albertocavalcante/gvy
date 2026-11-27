// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

/** The strings of the names pool: everything the names and extensions sections refer to. */
internal fun collectNameStrings(facts: Facts): Set<String> {
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
    for (fact in facts.extensions.entries) {
        strings += fact.holderClass
        strings += fact.moduleName
        strings += fact.moduleVersion
    }
    return strings
}

/** The strings of the type pool: everything the types section refers to, apart from the classes it describes. */
internal fun collectTypeStrings(facts: Facts): Set<String> {
    val strings = HashSet<String>()
    for (fact in facts.types.classes) {
        fact.superName?.let(strings::add)
        strings.addAll(fact.interfaces)
        for (member in fact.members) {
            strings += member.name
            strings += member.descriptor
            member.parameterNames?.let { names -> strings.addAll(names) }
            member.genericSignature?.let(strings::add)
        }
    }
    return strings
}

private fun requireDistinct(keys: List<Any>, what: String) {
    require(keys.toSet().size == keys.size) { "Duplicate $what" }
}

/** The classes of the names section in the order it is written; their positions are the class indices of types. */
internal fun sortedClasses(names: NamesSection, pool: StringPoolWriter): List<ClassFact> {
    val classes = names.classes.sortedWith(
        compareBy<ClassFact>({ fact -> pool.indexOf(fact.internalName) }, { fact -> fact.release }),
    )
    requireDistinct(classes.map { fact -> fact.internalName to fact.release }, "class and release")
    return classes
}

internal fun encodeNames(names: NamesSection, classes: List<ClassFact>, pool: StringPoolWriter): ByteArray {
    val packages = names.packages.sortedBy { fact -> pool.indexOf(fact.name) }
    requireDistinct(packages.map { fact -> fact.name }, "package name")
    val packageIndices = packages.withIndex().associate { (index, fact) -> fact.name to index }

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

/** Encodes the types section; each class is written as its index in [classes], the sorted names classes. */
internal fun encodeTypes(types: TypesSection, classes: List<ClassFact>, pool: StringPoolWriter): ByteArray {
    val classIndices = classes.withIndex().associate { (index, fact) -> (fact.internalName to fact.release) to index }
    val entries = types.classes
        .map { fact ->
            val index = requireNotNull(classIndices[fact.internalName to fact.release]) {
                "A class in the types section is not in the names section"
            }
            index to fact
        }
        .sortedBy { (index, _) -> index }
    requireDistinct(entries.map { (index, _) -> index }, "class and release")

    val sink = ByteSink()
    sink.writeVarint(entries.size)
    for ((index, fact) in entries) {
        sink.writeVarint(index)
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
    requireDistinct(entries, "extension")
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

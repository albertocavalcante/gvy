// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

private fun ByteCursor.readPooled(pool: StringPool): String = pool[readIndex(pool.size)]

/** Reads 0 as null, otherwise the pooled string at the index minus one. */
private fun ByteCursor.readOptionalPooled(pool: StringPool): String? {
    val encoded = readVarint()
    return if (encoded == 0) null else pool[checkedIndex(encoded - 1, pool.size)]
}

private fun checkedIndex(index: Int, limit: Int): Int {
    requireThat(index < limit, RejectReason.INDEX_OUT_OF_RANGE)
    return index
}

internal fun decodeNames(section: RawSection, pool: StringPool): NamesSection {
    val cursor = section.cursor()
    val packageCount = cursor.readVarint()
    cursor.requireCount(packageCount, MIN_PACKAGE_BYTES)
    val packages = ArrayList<PackageFact>(packageCount)
    repeat(packageCount) {
        packages += PackageFact(name = cursor.readPooled(pool), module = cursor.readOptionalPooled(pool))
    }
    val classCount = cursor.readVarint()
    cursor.requireCount(classCount, MIN_CLASS_BYTES)
    val classes = ArrayList<ClassFact>(classCount)
    repeat(classCount) {
        val internalName = cursor.readPooled(pool)
        val packageName = packages[cursor.readIndex(packages.size)].name
        classes += ClassFact(
            internalName = internalName,
            packageName = packageName,
            simpleName = cursor.readPooled(pool),
            outerClass = cursor.readOptionalPooled(pool),
            accessFlags = cursor.readAtMost(FactsFormat.MAX_ACCESS_FLAGS),
            release = cursor.readAtMost(FactsFormat.MAX_RELEASE),
        )
    }
    cursor.requireAtEnd()
    return NamesSection(packages, classes)
}

internal fun decodeTypes(section: RawSection, pool: StringPool): TypesSection {
    val cursor = section.cursor()
    val classCount = cursor.readVarint()
    cursor.requireCount(classCount, MIN_TYPES_CLASS_BYTES)
    val classes = ArrayList<ClassTypes>(classCount)
    repeat(classCount) { classes += decodeClassTypes(cursor, pool) }
    cursor.requireAtEnd()
    return TypesSection(classes)
}

private fun decodeClassTypes(cursor: ByteCursor, pool: StringPool): ClassTypes {
    val internalName = cursor.readPooled(pool)
    val release = cursor.readAtMost(FactsFormat.MAX_RELEASE)
    val superName = cursor.readOptionalPooled(pool)
    val interfaceCount = cursor.readVarint()
    cursor.requireCount(interfaceCount, 1)
    val interfaces = List(interfaceCount) { cursor.readPooled(pool) }
    val memberCount = cursor.readVarint()
    cursor.requireCount(memberCount, MIN_MEMBER_BYTES)
    val members = List(memberCount) { decodeMember(cursor, pool) }
    return ClassTypes(internalName, release, superName, interfaces, members)
}

private fun decodeMember(cursor: ByteCursor, pool: StringPool): MemberFact {
    val kind = MemberKind.entries.decodeEnum(cursor.readByte()) { it.code }
    val accessFlags = cursor.readAtMost(FactsFormat.MAX_ACCESS_FLAGS)
    val name = cursor.readPooled(pool)
    val descriptor = cursor.readPooled(pool)
    val parameterNameCount = cursor.readVarint()
    val parameterNames = if (parameterNameCount == 0) {
        null
    } else {
        cursor.requireCount(parameterNameCount - 1, 1)
        List(parameterNameCount - 1) { cursor.readPooled(pool) }
    }
    return MemberFact(kind, accessFlags, name, descriptor, parameterNames, cursor.readOptionalPooled(pool))
}

internal fun decodeExtensions(section: RawSection, pool: StringPool): ExtensionsSection {
    val cursor = section.cursor()
    val count = cursor.readVarint()
    cursor.requireCount(count, MIN_EXTENSION_BYTES)
    val entries = ArrayList<ExtensionFact>(count)
    repeat(count) {
        val holderClass = cursor.readPooled(pool)
        val kind = ExtensionKind.entries.decodeEnum(cursor.readByte()) { it.code }
        val source = ExtensionSource.entries.decodeEnum(cursor.readByte()) { it.code }
        entries += ExtensionFact(
            holderClass = holderClass,
            kind = kind,
            source = source,
            moduleName = cursor.readPooled(pool),
            moduleVersion = cursor.readPooled(pool),
        )
    }
    cursor.requireAtEnd()
    return ExtensionsSection(entries)
}

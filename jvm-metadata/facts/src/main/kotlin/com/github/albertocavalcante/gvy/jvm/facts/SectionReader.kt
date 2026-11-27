// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import java.util.Collections

/** The smallest encoded size of each repeated item, used to check a count against the remaining bytes. */
private const val MIN_PACKAGE_BYTES = 2
private const val MIN_CLASS_BYTES = 6
private const val MIN_TYPES_CLASS_BYTES = 4
private const val MIN_INTERFACE_BYTES = 1
private const val MIN_MEMBER_BYTES = 6
private const val MIN_PARAMETER_NAME_BYTES = 1
private const val MIN_EXTENSION_BYTES = 5

/** A collection sized from an untrusted count starts no larger than this; it grows as items are actually read. */
internal const val MAX_PRESIZE = 1024

/**
 * The decoded-item budget of one [FactsFile]: every pooled string, package, class, interface, member, parameter
 * name, extension entry and types entry costs one item, and each count is charged before anything is allocated for
 * it. Spending more than the limit rejects the input as oversized.
 */
internal class DecodeBudget(private var remaining: Long) {
    fun charge(count: Int) {
        requireThat(count <= remaining, RejectReason.OVERSIZED)
        remaining -= count
    }
}

/**
 * Accepts keys only in strictly increasing order, compared field by field. A key repeated or out of order is
 * corrupt, so a table has one encoding and its keys are unique.
 */
internal class StrictOrder {
    private var started = false
    private var first = 0
    private var second = 0
    private var third = 0
    private var fourth = 0
    private var fifth = 0

    fun accept(a: Int, b: Int = 0, c: Int = 0, d: Int = 0, e: Int = 0) {
        if (started) requireThat(isAfterPrevious(a, b, c, d, e), RejectReason.CORRUPT)
        started = true
        first = a
        second = b
        third = c
        fourth = d
        fifth = e
    }

    private fun isAfterPrevious(a: Int, b: Int, c: Int, d: Int, e: Int): Boolean = when {
        a != first -> a > first
        b != second -> b > second
        c != third -> c > third
        d != fourth -> d > fourth
        else -> e > fifth
    }
}

/**
 * Checks that [count] items of at least [minBytes] bytes can still be read, then charges [budget] for them. The
 * byte check comes first, so a count the input cannot hold is corrupt, and only a plausible one is oversized.
 */
internal fun ByteCursor.claim(count: Int, minBytes: Int, budget: DecodeBudget) {
    requireCount(count, minBytes)
    budget.charge(count)
}

/** Reads a count and claims it. */
internal fun ByteCursor.readCount(minBytes: Int, budget: DecodeBudget): Int {
    val count = readVarint()
    claim(count, minBytes, budget)
    return count
}

private inline fun <T> readItems(count: Int, read: () -> T): List<T> {
    val items = ArrayList<T>(minOf(count, MAX_PRESIZE))
    repeat(count) { items += read() }
    return Collections.unmodifiableList(items)
}

private fun ByteCursor.readPooled(pool: StringPool): String = pool[readIndex(pool.size)]

internal fun decodeNames(section: RawSection, pool: StringPool, budget: DecodeBudget): NamesSection {
    val cursor = section.cursor()
    val packageOrder = StrictOrder()
    val packages = readItems(cursor.readCount(MIN_PACKAGE_BYTES, budget)) {
        val name = cursor.readIndex(pool.size)
        val module = cursor.readOptionalIndex(pool.size)
        packageOrder.accept(name)
        PackageFact(pool[name], module?.let { pool[it] })
    }
    val classOrder = StrictOrder()
    val classes = readItems(cursor.readCount(MIN_CLASS_BYTES, budget)) {
        val internalName = cursor.readIndex(pool.size)
        val packageName = packages[cursor.readIndex(packages.size)].name
        val simpleName = cursor.readIndex(pool.size)
        val outerClass = cursor.readOptionalIndex(pool.size)
        val accessFlags = cursor.readAtMost(FactsFormat.MAX_ACCESS_FLAGS)
        val release = cursor.readAtMost(FactsFormat.MAX_RELEASE)
        classOrder.accept(internalName, release)
        ClassFact(pool[internalName], packageName, pool[simpleName], outerClass?.let { pool[it] }, accessFlags, release)
    }
    cursor.requireAtEnd()
    return NamesSection(packages, classes)
}

/** Decodes the types section; its classes are entries of [classes], the table of the names section. */
internal fun decodeTypes(
    section: RawSection,
    classes: List<ClassFact>,
    pool: StringPool,
    budget: DecodeBudget,
): TypesSection {
    val cursor = section.cursor()
    val classOrder = StrictOrder()
    val entries = readItems(cursor.readCount(MIN_TYPES_CLASS_BYTES, budget)) {
        val classIndex = cursor.readIndex(classes.size)
        val superName = cursor.readOptionalIndex(pool.size)
        val interfaces = readItems(cursor.readCount(MIN_INTERFACE_BYTES, budget)) { cursor.readPooled(pool) }
        val memberOrder = StrictOrder()
        val members = readItems(cursor.readCount(MIN_MEMBER_BYTES, budget)) {
            decodeMember(cursor, pool, budget, memberOrder)
        }
        classOrder.accept(classIndex)
        val owner = classes[classIndex]
        ClassTypes(owner.internalName, owner.release, superName?.let { pool[it] }, interfaces, members)
    }
    cursor.requireAtEnd()
    return TypesSection(entries)
}

private fun decodeMember(cursor: ByteCursor, pool: StringPool, budget: DecodeBudget, order: StrictOrder): MemberFact {
    val kind = MemberKind.entries.decodeEnum(cursor.readByte()) { it.code }
    val accessFlags = cursor.readAtMost(FactsFormat.MAX_ACCESS_FLAGS)
    val name = cursor.readIndex(pool.size)
    val descriptor = cursor.readIndex(pool.size)
    val parameterNameField = cursor.readVarint()
    val parameterNames = if (parameterNameField == 0) {
        null
    } else {
        cursor.claim(parameterNameField - 1, MIN_PARAMETER_NAME_BYTES, budget)
        readItems(parameterNameField - 1) { cursor.readPooled(pool) }
    }
    val signature = cursor.readOptionalIndex(pool.size)
    order.accept(name, descriptor)
    return MemberFact(kind, accessFlags, pool[name], pool[descriptor], parameterNames, signature?.let { pool[it] })
}

internal fun decodeExtensions(section: RawSection, pool: StringPool, budget: DecodeBudget): ExtensionsSection {
    val cursor = section.cursor()
    val order = StrictOrder()
    val entries = readItems(cursor.readCount(MIN_EXTENSION_BYTES, budget)) {
        val holderClass = cursor.readIndex(pool.size)
        val kind = ExtensionKind.entries.decodeEnum(cursor.readByte()) { it.code }
        val source = ExtensionSource.entries.decodeEnum(cursor.readByte()) { it.code }
        val moduleName = cursor.readIndex(pool.size)
        val moduleVersion = cursor.readIndex(pool.size)
        order.accept(holderClass, kind.code, source.code, moduleName, moduleVersion)
        ExtensionFact(pool[holderClass], kind, source, pool[moduleName], pool[moduleVersion])
    }
    cursor.requireAtEnd()
    return ExtensionsSection(entries)
}

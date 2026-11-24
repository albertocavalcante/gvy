// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.BitSet

/** Why an entry was left out of the class index although it was meant to be a class. */
internal enum class SkipReason(val label: String) {
    UNREADABLE("unreadable or truncated"),
    NOT_A_CLASS_FILE("not a class file"),
    NAME_MISMATCH("class name does not match its path"),
    OVERSIZED("larger than the per-class limit"),
    UNKNOWN_CONSTANT_TAG("unknown constant pool tag"),
    TOO_MANY_ENTRIES("archive has too many entries"),
}

internal sealed interface ClassFileRead {
    data class Accepted(val flags: ClassAccessFlags) : ClassFileRead

    data class Rejected(val reason: SkipReason) : ClassFileRead
}

/** A class file, or a stream standing in for one, is longer than the reader's limit. */
internal class ClassFileTooLargeException(limit: Long) : IOException("Class file exceeds $limit bytes")

/**
 * Reads bytes from [input] through one caller-owned [buffer], never more than [limit] in total, so a stream that
 * lies about its size cannot make the reader work for long. Running out of input throws [EOFException].
 *
 * Reading through our own buffer, instead of wrapping each class in a fresh `BufferedInputStream` (which would
 * allocate its own 8 KiB per class), shares one read buffer across the whole index build; the sources (zip
 * inflater, file channel, module reader) are not buffered themselves, so byte-wise reads straight from them would
 * be slow. [readClassFile] still allocates a pool-count sized `IntArray` and `BitSet` per class (at most about
 * 300 KiB, typically a few KiB), which become garbage as soon as the class is read.
 */
internal class ClassFileCursor(private val input: InputStream, private val buffer: ByteArray, private val limit: Long) {
    private var position = 0
    private var end = 0
    private var consumed = 0L

    private fun refill() {
        val room = minOf(buffer.size.toLong(), limit - consumed).toInt()
        if (room <= 0) throw ClassFileTooLargeException(limit)
        val count = input.read(buffer, 0, room)
        // A read of zero bytes for a non-zero request is treated as the end, or skipping would never finish.
        if (count <= 0) throw EOFException()
        position = 0
        end = count
        consumed += count
    }

    fun u1(): Int {
        if (position == end) refill()
        return buffer[position++].toInt() and BYTE_MASK
    }

    fun u2(): Int = (u1() shl BYTE_BITS) or u1()

    fun u4(): Int = (u2() shl SHORT_BITS) or u2()

    fun skip(count: Int) {
        var left = count
        while (left > 0) {
            if (position == end) refill()
            val step = minOf(left, end - position)
            position += step
            left -= step
        }
    }

    /** Consumes [length] bytes and reports whether they equal [expected]; no buffer is sized from [length]. */
    fun consumeAndCompare(length: Int, expected: ByteArray): Boolean {
        if (length != expected.size) {
            skip(length)
            return false
        }
        var equal = true
        for (byte in expected) {
            if (u1() != (byte.toInt() and BYTE_MASK)) equal = false
        }
        return equal
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val BYTE_BITS = 8
        const val SHORT_BITS = 16
    }
}

private const val CLASS_FILE_MAGIC = 0xCAFEBABE.toInt()
private const val VERSION_BYTES = 4 // minor_version and major_version

// Constant pool tags, JVMS §4.4 Table 4.4-B.
private const val TAG_UTF8 = 1
private const val TAG_INTEGER = 3
private const val TAG_FLOAT = 4
private const val TAG_LONG = 5
private const val TAG_DOUBLE = 6
private const val TAG_CLASS = 7
private const val TAG_STRING = 8
private const val TAG_FIELD_REF = 9
private const val TAG_METHOD_REF = 10
private const val TAG_INTERFACE_METHOD_REF = 11
private const val TAG_NAME_AND_TYPE = 12
private const val TAG_METHOD_HANDLE = 15
private const val TAG_METHOD_TYPE = 16
private const val TAG_DYNAMIC = 17
private const val TAG_INVOKE_DYNAMIC = 18
private const val TAG_MODULE = 19
private const val TAG_PACKAGE = 20

// Payload widths of the entries with a fixed size.
private const val INDEX_WIDTH = 2
private const val METHOD_HANDLE_WIDTH = 3
private const val PAIR_WIDTH = 4
private const val WIDE_WIDTH = 8

/** Payload bytes after the tag of a fixed-size entry, or null for a tag this reader does not know. */
private fun fixedWidth(tag: Int): Int? = when (tag) {
    TAG_CLASS, TAG_STRING, TAG_METHOD_TYPE, TAG_MODULE, TAG_PACKAGE -> INDEX_WIDTH
    TAG_METHOD_HANDLE -> METHOD_HANDLE_WIDTH
    TAG_INTEGER, TAG_FLOAT, TAG_FIELD_REF, TAG_METHOD_REF, TAG_INTERFACE_METHOD_REF, TAG_NAME_AND_TYPE,
    TAG_DYNAMIC, TAG_INVOKE_DYNAMIC,
    -> PAIR_WIDTH
    TAG_LONG, TAG_DOUBLE -> WIDE_WIDTH
    else -> null
}

/**
 * Reads a class file's header (JVMS §4.1) just far enough to learn its `access_flags`, and checks that its
 * `this_class` names [internalName] (for example `java/util/Map$Entry` as UTF-8), so an archive entry whose path
 * is not the class's own name, such as `BOOT-INF/classes/com/acme/Foo.class`, is rejected.
 *
 * The constant pool (§4.4) is streamed: each Utf8 entry is compared with [internalName] as it passes, every Class
 * entry's `name_index` is kept, and nothing else is retained, so memory is bounded by the pool's 16-bit entry count
 * (at most about 300 KiB) whatever the file claims. Throws [IOException] for truncated or over-long input.
 */
internal fun readClassFile(cursor: ClassFileCursor, internalName: ByteArray): ClassFileRead {
    if (cursor.u4() != CLASS_FILE_MAGIC) return ClassFileRead.Rejected(SkipReason.NOT_A_CLASS_FILE)
    cursor.skip(VERSION_BYTES)
    val poolCount = cursor.u2()
    val classNameIndex = IntArray(poolCount)
    val nameMatches = BitSet(poolCount)
    var slot = 1
    while (slot < poolCount) {
        var slots = 1
        when (val tag = cursor.u1()) {
            TAG_UTF8 -> if (cursor.consumeAndCompare(cursor.u2(), internalName)) nameMatches.set(slot)
            TAG_CLASS -> classNameIndex[slot] = cursor.u2()
            else -> {
                val width = fixedWidth(tag) ?: return ClassFileRead.Rejected(SkipReason.UNKNOWN_CONSTANT_TAG)
                cursor.skip(width)
                // Long and double constants take two slots (§4.4.5).
                if (tag == TAG_LONG || tag == TAG_DOUBLE) slots = 2
            }
        }
        slot += slots
    }
    val flags = ClassAccessFlags(cursor.u2())
    val thisClass = cursor.u2()
    val nameIndex = classNameIndex.getOrElse(thisClass) { 0 }
    if (nameIndex == 0 || !nameMatches[nameIndex]) return ClassFileRead.Rejected(SkipReason.NAME_MISMATCH)
    return ClassFileRead.Accepted(flags)
}

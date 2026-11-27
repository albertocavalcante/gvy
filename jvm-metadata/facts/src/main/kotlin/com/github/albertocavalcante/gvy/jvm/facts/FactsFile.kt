// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

/** Why a facts file, or one of its sections, was refused. Rejections never carry a path or file content. */
public enum class RejectReason {
    /** The file does not start with the magic bytes. */
    BAD_MAGIC,

    /** The major version is not [FactsFormat.MAJOR]. */
    UNSUPPORTED_VERSION,

    /** The file ends before the header, the section table or a section is complete. */
    TRUNCATED,

    /**
     * The content is malformed: a checksum differs, a varint or UTF-8 string is invalid, a stream inflates to a
     * different length than declared, a required section is missing, or a count or length is inconsistent.
     */
    CORRUPT,

    /** The file, a section or a string is above a limit: [ReadLimits], [FactsFormat], or the inflate ratio. */
    OVERSIZED,

    /** An index refers outside the string pool or the table it points into. */
    INDEX_OUT_OF_RANGE,

    /** The header's input SHA-256 differs from the one the caller expected. */
    INPUT_MISMATCH,
}

/** The outcome of reading a facts file or one of its sections: a value, or a typed rejection. Never null. */
public sealed interface LoadResult<out T> {
    /**
     * The decoded [value]. [bytesRead] counts the stored bytes this call needed: the header and section table for
     * [FactsFile.open], and for a section the stored length of that section plus the string pool.
     */
    public data class Loaded<out T>(val value: T, val bytesRead: Int) : LoadResult<T>

    /** The input was refused for [reason]. */
    public data class Rejected(val reason: RejectReason) : LoadResult<Nothing>
}

/**
 * Caps on untrusted input, checked before anything is allocated for it.
 *
 * [maxFileBytes] bounds the whole file. [maxSectionBytes] bounds the declared raw length of each section, which
 * is what inflating one allocates. A DEFLATE section is also refused when its raw length exceeds
 * [FactsFormat.MAX_INFLATE_RATIO] times its stored length, so a file can make a reader allocate at most that
 * multiple of its own size, and never more than [maxSectionBytes] per section.
 */
public class ReadLimits(
    public val maxFileBytes: Int = DEFAULT_MAX_FILE_BYTES,
    public val maxSectionBytes: Int = DEFAULT_MAX_SECTION_BYTES,
) {
    init {
        require(maxFileBytes > 0 && maxSectionBytes > 0) { "Limits must be positive" }
    }
}

internal const val DEFAULT_MAX_FILE_BYTES = 64 * 1024 * 1024
internal const val DEFAULT_MAX_SECTION_BYTES = 128 * 1024 * 1024

/**
 * A facts file whose header and section table were checked and whose sections are decoded on demand. Opening reads
 * no section; [names], [types] and [extensions] each inflate and verify only their own section and the string
 * pool, so a file with a damaged [types] section still yields [names].
 */
public class FactsFile internal constructor() {
    /** The identity recorded in the header. */
    public val header: FactsHeader get() = TODO("slice 1: the reader")

    /** The minor version of the file, which can be newer than [FactsFormat.MINOR]. */
    public val formatMinor: Int get() = TODO("slice 1: the reader")

    /** Decodes the package and class names. */
    public fun names(): LoadResult<NamesSection> = TODO("slice 1: the reader")

    /** Decodes the supertypes and members. */
    public fun types(): LoadResult<TypesSection> = TODO("slice 1: the reader")

    /** Decodes the extension holders. */
    public fun extensions(): LoadResult<ExtensionsSection> = TODO("slice 1: the reader")

    /** Decodes every section; one rejected section rejects the result. */
    public fun loadAll(): LoadResult<Facts> = TODO("slice 1: the reader")

    public companion object {
        /** Checks the header and the section table of [bytes] without reading any section. */
        public fun open(
            bytes: ByteArray,
            limits: ReadLimits = ReadLimits(),
            expectedInputSha256: Sha256? = null,
        ): LoadResult<FactsFile> = TODO("slice 1: the reader of ${bytes.size} bytes, $limits, $expectedInputSha256")
    }
}

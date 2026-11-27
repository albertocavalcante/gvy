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
 *
 * Methods never throw for malformed input. A section result is computed on each call, except the string pool,
 * which is decoded once. The file keeps the byte array given to [open] and does not copy it.
 *
 * This type works on bytes the caller supplies and reads no files: reading a file safely (refusing a symlink,
 * applying the size cap before reading) is the caller's job.
 */
public class FactsFile internal constructor(
    private val bytes: ByteArray,
    private val limits: ReadLimits,
    private val parsed: ParsedFile,
) {
    /** The identity recorded in the header. It is not verified against the input; see `expectedInputSha256`. */
    public val header: FactsHeader get() = parsed.header

    /** The minor version of the file, which can be newer than [FactsFormat.MINOR]. */
    public val formatMinor: Int get() = parsed.formatMinor

    private val pool: LoadResult<StringPool> by lazy {
        attempt(storedBytes(SECTION_STRINGS)) { decodeStringPool(rawSection(SECTION_STRINGS)) }
    }

    /** Decodes the package and class names. */
    public fun names(): LoadResult<NamesSection> =
        attempt(storedBytes(SECTION_STRINGS, SECTION_NAMES)) { decodeNames(rawSection(SECTION_NAMES), stringPool()) }

    /** Decodes the supertypes and members. */
    public fun types(): LoadResult<TypesSection> =
        attempt(storedBytes(SECTION_STRINGS, SECTION_TYPES)) { decodeTypes(rawSection(SECTION_TYPES), stringPool()) }

    /** Decodes the extension holders. */
    public fun extensions(): LoadResult<ExtensionsSection> = attempt(
        storedBytes(SECTION_STRINGS, SECTION_EXTENSIONS),
    ) { decodeExtensions(rawSection(SECTION_EXTENSIONS), stringPool()) }

    /** Decodes every section; one rejected section rejects the result. */
    public fun loadAll(): LoadResult<Facts> = attempt(
        storedBytes(SECTION_STRINGS, SECTION_NAMES, SECTION_TYPES, SECTION_EXTENSIONS),
    ) {
        val strings = stringPool()
        Facts(
            header = header,
            names = decodeNames(rawSection(SECTION_NAMES), strings),
            types = decodeTypes(rawSection(SECTION_TYPES), strings),
            extensions = decodeExtensions(rawSection(SECTION_EXTENSIONS), strings),
        )
    }

    private fun stringPool(): StringPool = when (val result = pool) {
        is LoadResult.Loaded -> result.value
        is LoadResult.Rejected -> reject(result.reason)
    }

    private fun storedBytes(vararg ids: Int): Int =
        ids.sumOf { id -> (parsed.sections[id]?.storedLength ?: 0).toLong() }.toInt()

    private fun rawSection(id: Int): RawSection {
        val entry = parsed.sections[id] ?: reject(RejectReason.CORRUPT)
        requireThat(entry.rawLength <= limits.maxSectionBytes, RejectReason.OVERSIZED)
        val raw = when (entry.codec) {
            CODEC_NONE -> {
                requireThat(entry.storedLength == entry.rawLength, RejectReason.CORRUPT)
                RawSection(bytes, entry.offset, entry.rawLength)
            }
            CODEC_DEFLATE -> {
                val ratioLimit = entry.storedLength.toLong() * FactsFormat.MAX_INFLATE_RATIO
                requireThat(entry.rawLength <= ratioLimit, RejectReason.OVERSIZED)
                RawSection(inflateExactly(bytes, entry.offset, entry.storedLength, entry.rawLength), 0, entry.rawLength)
            }
            else -> reject(RejectReason.CORRUPT)
        }
        requireThat(crc32c(raw.bytes, raw.offset, raw.length) == entry.rawCrc32c, RejectReason.CORRUPT)
        return raw
    }

    private inline fun <T> attempt(bytesRead: Int, block: () -> T): LoadResult<T> = try {
        LoadResult.Loaded(block(), bytesRead)
    } catch (rejection: Rejection) {
        LoadResult.Rejected(rejection.reason)
    }

    public companion object {
        /**
         * Checks the header and the section table of [bytes] without reading any section. [bytes] is held, not
         * copied, so the caller must not change it while the file is in use.
         *
         * Checked here: [ReadLimits.maxFileBytes]; the magic; the major version; that the table lies within the
         * file and that the sections fill the rest of it exactly, with no id twice; and, when [expectedInputSha256]
         * is given, that the header names that input. Section ids this reader does not know are ignored.
         * Everything about a section is checked when that section is decoded.
         *
         * Never throws for malformed input.
         */
        public fun open(
            bytes: ByteArray,
            limits: ReadLimits = ReadLimits(),
            expectedInputSha256: Sha256? = null,
        ): LoadResult<FactsFile> = try {
            val parsed = parseFile(bytes, limits, expectedInputSha256)
            LoadResult.Loaded(FactsFile(bytes, limits, parsed), parsed.headerLength)
        } catch (rejection: Rejection) {
            LoadResult.Rejected(rejection.reason)
        }
    }
}

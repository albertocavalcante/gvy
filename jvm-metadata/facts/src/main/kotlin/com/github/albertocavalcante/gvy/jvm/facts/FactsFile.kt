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

    /** The file, a section, a string or the decoded items are above a limit: [ReadLimits], [FactsFormat], the ratio. */
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
     * [FactsFile.open], and for a section the stored length of that section and of the sections it depends on.
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
 * [FactsFormat.MAX_INFLATE_RATIO] times its stored length, so inflating allocates at most that multiple of the
 * file's size, and never more than [maxSectionBytes] per section. Both default to 64 MiB: a section cannot be
 * larger than the file cap says a file may be, and an honest JDK image is far smaller.
 *
 * [maxDecodedItems] bounds what the objects decoded from the sections can cost. The smallest items, a class without
 * members or a one-byte string, take about 108 heap bytes each, tens of times their encoded size, so the raw size
 * alone does not bound the heap. One budget serves a [FactsFile]: every pooled string, package, class, interface,
 * member, parameter name, extension entry and types entry costs one item, charged before the objects are
 * allocated, and a section is decoded and charged once however often it is asked for. The default, 3 million
 * items, is three times the roughly 1 million a full JDK image needs (about 40 thousand classes, counted in the
 * names and the types section, 60 thousand interface references, 500 thousand members, 250 thousand pooled
 * strings and 100 thousand parameter names), and 3 million items take about 324 MB in the worst case, far below
 * the 1 GiB heap the experiment runs with.
 */
public class ReadLimits(
    public val maxFileBytes: Int = DEFAULT_MAX_FILE_BYTES,
    public val maxSectionBytes: Int = DEFAULT_MAX_SECTION_BYTES,
    public val maxDecodedItems: Int = DEFAULT_MAX_DECODED_ITEMS,
) {
    init {
        require(maxFileBytes > 0 && maxSectionBytes > 0 && maxDecodedItems > 0) { "Limits must be positive" }
    }
}

internal const val DEFAULT_MAX_FILE_BYTES = 64 * 1024 * 1024
internal const val DEFAULT_MAX_SECTION_BYTES = 64 * 1024 * 1024
internal const val DEFAULT_MAX_DECODED_ITEMS = 3_000_000

/** A section's decoded value, computed once under the file's lock; a rejection is remembered too. */
private class Memo<T : Any>(private val decode: () -> T) {
    private var value: T? = null
    private var reason: RejectReason? = null

    fun get(): T {
        value?.let { return it }
        reason?.let { reject(it) }
        try {
            return decode().also { value = it }
        } catch (rejection: Rejection) {
            reason = rejection.reason
            throw rejection
        }
    }
}

/**
 * A facts file whose header and section table were checked and whose sections are decoded on demand. Opening reads
 * no section. [names] decodes the names pool and the names section; [extensions] the names pool and the extensions
 * section; [types] those of the names, plus the type pool and the types section; [loadAll] everything. A section is
 * never decoded unless a call needs it, so a file with a damaged [types] section or type pool still yields [names]
 * and [extensions].
 *
 * Methods never throw for malformed input. Sections are decoded in this order whichever call needs them: names
 * pool, names, extensions, type pool, types; so every call reports the same rejection for the same file. Each
 * section is decoded, and charged to [ReadLimits.maxDecodedItems], once, and the result is kept. The file keeps the
 * byte array given to [open] and does not copy it.
 *
 * It is safe to share between threads: decoding runs under one lock, decoded values are immutable, and the byte
 * array is only read, so callers must not change it while the file is in use.
 *
 * This type works on bytes the caller supplies and reads no files: reading a file safely (refusing a symlink,
 * applying the size cap before reading) is the caller's job.
 */
public class FactsFile internal constructor(
    private val bytes: ByteArray,
    private val limits: ReadLimits,
    private val parsed: ParsedFile,
) {
    /**
     * The identity recorded in the header. It is not verified against the input, and nothing in the file protects
     * it: see `expectedInputSha256` of [open].
     */
    public val header: FactsHeader get() = parsed.header

    /** The minor version of the file, which can be newer than [FactsFormat.MINOR]. */
    public val formatMinor: Int get() = parsed.formatMinor

    private val lock = Any()
    private val budget = DecodeBudget(limits.maxDecodedItems.toLong())
    private val namePool = Memo { decodeStringPool(rawSection(SECTION_NAME_STRINGS), budget) }
    private val names = Memo {
        val pool = namePool.get()
        decodeNames(rawSection(SECTION_NAMES), pool, budget)
    }
    private val extensions = Memo {
        val pool = namePool.get()
        decodeExtensions(rawSection(SECTION_EXTENSIONS), pool, budget)
    }
    private val typePool = Memo { decodeStringPool(rawSection(SECTION_TYPE_STRINGS), budget) }
    private val types = Memo {
        val classes = names.get().classes
        val pool = typePool.get()
        decodeTypes(rawSection(SECTION_TYPES), classes, pool, budget)
    }

    /** Decodes the package and class names. */
    public fun names(): LoadResult<NamesSection> = load(listOf(SECTION_NAME_STRINGS, SECTION_NAMES)) { names.get() }

    /** Decodes the supertypes and members. This needs the names, whose class table the types section indexes. */
    public fun types(): LoadResult<TypesSection> = load(
        listOf(SECTION_NAME_STRINGS, SECTION_NAMES, SECTION_TYPE_STRINGS, SECTION_TYPES),
    ) { types.get() }

    /** Decodes the extension holders. */
    public fun extensions(): LoadResult<ExtensionsSection> =
        load(listOf(SECTION_NAME_STRINGS, SECTION_EXTENSIONS)) { extensions.get() }

    /** Decodes every section; one rejected section rejects the result. */
    public fun loadAll(): LoadResult<Facts> = load(ALL_SECTIONS) {
        val decodedNames = names.get()
        val decodedExtensions = extensions.get()
        Facts(header, decodedNames, types.get(), decodedExtensions)
    }

    private inline fun <T> load(ids: List<Int>, decode: () -> T): LoadResult<T> = synchronized(lock) {
        try {
            LoadResult.Loaded(decode(), storedBytes(ids))
        } catch (rejection: Rejection) {
            LoadResult.Rejected(rejection.reason)
        }
    }

    private fun storedBytes(ids: List<Int>): Int =
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

    public companion object {
        private val ALL_SECTIONS = listOf(
            SECTION_NAME_STRINGS,
            SECTION_NAMES,
            SECTION_EXTENSIONS,
            SECTION_TYPE_STRINGS,
            SECTION_TYPES,
        )

        /**
         * Checks the header and the section table of [bytes] without reading any section. [bytes] is held, not
         * copied, so the caller must not change it while the file is in use.
         *
         * Checked here: [ReadLimits.maxFileBytes]; the magic; the major version; the header fields and their limits;
         * that the table lies within the file, that no length in it is 2^31 or more, and that the sections fill the
         * rest of the file exactly, with no id twice; and, when [expectedInputSha256] is given, that the header names
         * that input. Section ids this reader does not know are otherwise ignored. Everything else about a section,
         * including every index in it, is checked when that section is decoded.
         *
         * The header's input SHA-256 and length are not protected by the file: the header has no checksum, so a
         * damaged or forged file can carry any digest. A caller that depends on the file describing a given input
         * must pass [expectedInputSha256], and must still obtain that digest from the input it trusts.
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

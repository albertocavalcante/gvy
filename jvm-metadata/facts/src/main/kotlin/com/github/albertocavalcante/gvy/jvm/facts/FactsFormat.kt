// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

/**
 * Constants of the facts file format. The byte layout is specified once, in `FORMAT.md` beside this module.
 */
public object FactsFormat {
    /** The major version this module writes and reads; a file with another major version is rejected. */
    public const val MAJOR: Int = 1

    /**
     * The minor version this module writes. A file with a newer minor version loads: a minor version only adds
     * sections, and a reader skips section ids it does not know.
     */
    public const val MINOR: Int = 0

    /** The longest pooled string in UTF-8 bytes, the limit of a class-file UTF-8 constant. */
    public const val MAX_STRING_BYTES: Int = 65_535

    /** The longest producer version or coordinate in the header, in UTF-8 bytes. */
    public const val MAX_HEADER_STRING_BYTES: Int = 4_096

    /** A DEFLATE section is rejected when its declared raw length exceeds this multiple of its stored length. */
    public const val MAX_INFLATE_RATIO: Int = 50

    /**
     * The access-flag bit that marks a deprecated class or member. The bits below it are the JVM access flags;
     * the bits above [MAX_ACCESS_FLAGS] are not used.
     */
    public const val DEPRECATED_FLAG: Int = 0x10000

    /** The largest valid access-flags value: the 16 JVM bits plus [DEPRECATED_FLAG]. */
    public const val MAX_ACCESS_FLAGS: Int = 0x1FFFF

    /** The largest multi-release version a class can carry; 0 means the base copy. */
    public const val MAX_RELEASE: Int = 255

    internal const val MAX_SECTIONS: Int = 64

    internal val MAGIC: ByteArray = "GVYF".toByteArray(Charsets.US_ASCII)
}

internal const val SECTION_NAME_STRINGS = 1
internal const val SECTION_NAMES = 2
internal const val SECTION_EXTENSIONS = 3
internal const val SECTION_TYPE_STRINGS = 4
internal const val SECTION_TYPES = 5

internal const val CODEC_NONE = 0
internal const val CODEC_DEFLATE = 1

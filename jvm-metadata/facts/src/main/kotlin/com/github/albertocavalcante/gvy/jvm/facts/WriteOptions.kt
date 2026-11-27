// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import java.util.zip.Deflater

internal const val DEFAULT_DEFLATE_LEVEL = 6

/**
 * How one section is stored: not compressed, or DEFLATE at a level from 1 (fastest) to 9 (smallest). The choice
 * changes the stored bytes only; the raw bytes of a section, and so its checksum, do not depend on it.
 */
public class SectionEncoding private constructor(internal val codec: Int, internal val level: Int) {
    public companion object {
        /** Stores the raw bytes as they are. */
        public val NONE: SectionEncoding = SectionEncoding(CODEC_NONE, 0)

        /**
         * Compresses with [java.util.zip.Deflater] at [level], which must be in 1..9. [FactsWriter.write] stores a
         * section without compression instead when DEFLATE would shrink it beyond [FactsFormat.MAX_INFLATE_RATIO].
         */
        public fun deflate(level: Int): SectionEncoding {
            require(level in Deflater.BEST_SPEED..Deflater.BEST_COMPRESSION) {
                "A DEFLATE level must be in ${Deflater.BEST_SPEED}..${Deflater.BEST_COMPRESSION}, got $level"
            }
            return SectionEncoding(CODEC_DEFLATE, level)
        }
    }
}

internal val DEFAULT_ENCODING: SectionEncoding = SectionEncoding.deflate(DEFAULT_DEFLATE_LEVEL)

/** The encoding of each section; each defaults to DEFLATE at level 6. */
public class WriteOptions(
    public val strings: SectionEncoding = DEFAULT_ENCODING,
    public val names: SectionEncoding = DEFAULT_ENCODING,
    public val types: SectionEncoding = DEFAULT_ENCODING,
    public val extensions: SectionEncoding = DEFAULT_ENCODING,
) {
    internal fun encodingOf(sectionId: Int): SectionEncoding = when (sectionId) {
        SECTION_STRINGS -> strings
        SECTION_NAMES -> names
        SECTION_TYPES -> types
        SECTION_EXTENSIONS -> extensions
        else -> error("Unknown section id $sectionId")
    }
}

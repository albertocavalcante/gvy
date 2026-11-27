// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

/** Why the builder left one entry of an input out of the facts. Counted per reason; never carries a path. */
public enum class SkipReason {
    /** The bytes are not a class file this reader can parse, or they are damaged. */
    MALFORMED,

    /** The class file is newer than the class-file version the ASM in use reads. */
    UNSUPPORTED_CLASS_VERSION,

    /** The class names itself differently from the path it was found at. */
    NAME_MISMATCH,

    /** The path does not name a valid class: an empty segment, or a dot, semicolon or bracket in the name. */
    INVALID_NAME,

    /** The class file is larger than the configured per-class limit. */
    OVERSIZED,

    /** An archive entry could not be read: it is encrypted, uses an unsupported method, or failed to read. */
    UNREADABLE_ENTRY,

    /** An archive holds a second entry with the same name; the first one is kept. */
    DUPLICATE_ENTRY,

    /** The path sits under a `META-INF/versions/` directory that is not a release from 1 to 255. */
    UNSUPPORTED_RELEASE,
}

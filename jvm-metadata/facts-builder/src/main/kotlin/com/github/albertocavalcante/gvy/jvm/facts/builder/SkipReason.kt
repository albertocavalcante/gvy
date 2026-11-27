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

    /**
     * The path does not name a valid class: it has an empty segment, a dot, semicolon or bracket, or a name the
     * facts format cannot store.
     */
    INVALID_NAME,

    /** The path sits under a `META-INF/versions/` directory that is not a release from 1 to 255. */
    UNSUPPORTED_RELEASE,
}

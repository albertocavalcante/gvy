// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import com.github.albertocavalcante.gvy.jvm.facts.ClassFact
import com.github.albertocavalcante.gvy.jvm.facts.ClassTypes

/** The outcome of reading one class file. */
internal sealed interface Extraction {
    /** A class that was read. */
    data class Read(
        val fact: ClassFact,
        val types: ClassTypes?,
        val droppedMembers: Int,
        val droppedParameterNames: Int,
        val droppedSignatures: Int,
        val typesOmitted: Boolean,
        val visibilityDisagreement: Boolean,
    ) : Extraction

    /** A class file the builder does not keep. */
    data class Skipped(val reason: SkipReason) : Extraction
}

/** Turns the bytes of one class file into facts. */
internal object ClassFileExtractor {
    /** This first version keeps nothing, so that the tests can fail before the extraction exists. */
    @Suppress("UNUSED_PARAMETER")
    fun extract(expectedName: String, release: Int, bytes: ByteArray, includeGenericSignatures: Boolean): Extraction =
        Extraction.Skipped(SkipReason.MALFORMED)
}

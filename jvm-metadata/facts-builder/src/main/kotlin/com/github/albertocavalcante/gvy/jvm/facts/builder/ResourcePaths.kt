// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

/** What a resource path of an archive means to the builder. */
internal sealed interface ResourceClass {
    /** A class to read: its internal name and its release (0 for the base copy). */
    data class Candidate(val internalName: String, val release: Int) : ResourceClass

    /** Not a class the builder reads, and not counted: other resources, module and package descriptors. */
    object NotAClass : ResourceClass

    /** A class path the builder refuses, counted under [reason]. */
    data class Skip(val reason: SkipReason) : ResourceClass
}

/** Maps archive resource paths to classes. Pure; the rules are in the design of the builder. */
internal object ResourcePaths {
    /** Classifies [path]; this first version reads nothing, so that the tests can fail before the rules exist. */
    fun classify(path: String): ResourceClass = ResourceClass.NotAClass

    /** Whether [name] can be the internal name of a class. */
    fun isValidInternalName(name: String): Boolean = name.isNotEmpty()
}

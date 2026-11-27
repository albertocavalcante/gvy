// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

private const val PRIMITIVE_TYPES = "BCDFIJSZ"
private const val MAX_ARRAY_DIMENSIONS = 255

/**
 * Checks of JVM descriptors (JVMS 4.3). A descriptor that fails is not stored: the model has no use for it and a
 * consumer would have to guess what it meant.
 */
internal object Descriptors {
    /** Whether [descriptor] is exactly one field type. */
    fun isFieldDescriptor(descriptor: String): Boolean = fieldTypeEnd(descriptor, 0) == descriptor.length

    /** The number of parameters of the method descriptor [descriptor], or null when it is not a method descriptor. */
    fun parameterCount(descriptor: String): Int? {
        var index = 1
        var count = 0
        var valid = descriptor.startsWith("(")
        while (valid && index < descriptor.length && descriptor[index] != ')') {
            index = fieldTypeEnd(descriptor, index)
            valid = index >= 0
            count++
        }
        return if (valid && index < descriptor.length && returnTypeEnd(descriptor, index + 1) == descriptor.length) {
            count
        } else {
            null
        }
    }
}

private fun returnTypeEnd(text: String, start: Int): Int =
    if (text.startsWith("V", start)) start + 1 else fieldTypeEnd(text, start)

/** The index after the field type that starts at [start] in [text], or -1 when none does. */
private fun fieldTypeEnd(text: String, start: Int): Int {
    var index = start
    while (index < text.length && text[index] == '[') index++
    return when {
        index - start > MAX_ARRAY_DIMENSIONS || index >= text.length -> -1
        text[index] in PRIMITIVE_TYPES -> index + 1
        text[index] == 'L' -> classTypeEnd(text, index + 1)
        else -> -1
    }
}

/** The index after the `name;` that starts at [nameStart], or -1 when the name is not a valid internal name. */
private fun classTypeEnd(text: String, nameStart: Int): Int {
    val semicolon = text.indexOf(';', nameStart)
    val validName = semicolon > nameStart && ResourcePaths.isValidInternalName(text.substring(nameStart, semicolon))
    return if (validName) semicolon + 1 else -1
}

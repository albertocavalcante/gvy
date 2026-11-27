// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import com.github.albertocavalcante.gvy.jvm.facts.FactsFormat

/** What a resource path of an archive means to the builder. */
internal sealed interface ResourceClass {
    /** A class to read: its internal name and its release (0 for the base copy). */
    data class Candidate(val internalName: String, val release: Int) : ResourceClass

    /** Not a class the builder reads, and not counted: other resources, module and package descriptors. */
    object NotAClass : ResourceClass

    /** A class path the builder refuses, counted under [reason]. */
    data class Skip(val reason: SkipReason) : ResourceClass
}

private const val CLASS_SUFFIX = ".class"
private const val META_INF = "META-INF/"
private const val VERSIONS = "META-INF/versions/"
private const val MAX_RELEASE_DIGITS = 3

/** Maps archive resource paths to classes. Pure; the rules are in the design of the builder. */
internal object ResourcePaths {
    /**
     * Classifies [path], a `/`-separated resource path inside an archive or below a module of a JDK image.
     *
     * `module-info.class` and `package-info.class` are never classes, at any depth. Under `META-INF/` only
     * `META-INF/versions/N/` holds classes, with N a canonical decimal from 1 to [FactsFormat.MAX_RELEASE];
     * the `Multi-Release` manifest attribute is not consulted.
     */
    fun classify(path: String): ResourceClass = when {
        !path.endsWith(CLASS_SUFFIX) -> ResourceClass.NotAClass
        path.startsWith(VERSIONS) -> classifyVersioned(path.substring(VERSIONS.length))
        path.startsWith(META_INF) -> ResourceClass.NotAClass
        else -> classifyName(path, 0)
    }

    /** Whether [name] can be the internal name of a class: no empty segment and none of `.`, `;` or `[`. */
    fun isValidInternalName(name: String): Boolean =
        name.isNotEmpty() &&
            name.none { it == '.' || it == ';' || it == '[' } &&
            !name.startsWith('/') &&
            !name.endsWith('/') &&
            !name.contains("//")
}

private fun classifyVersioned(afterVersions: String): ResourceClass {
    val slash = afterVersions.indexOf('/')
    if (slash < 0) return ResourceClass.NotAClass
    val release = parseRelease(afterVersions.substring(0, slash))
    return if (release == null) {
        ResourceClass.Skip(SkipReason.UNSUPPORTED_RELEASE)
    } else {
        classifyName(afterVersions.substring(slash + 1), release)
    }
}

private fun classifyName(classPath: String, release: Int): ResourceClass {
    val internalName = classPath.removeSuffix(CLASS_SUFFIX)
    val fileName = internalName.substringAfterLast('/')
    return when {
        fileName == "module-info" || fileName == "package-info" -> ResourceClass.NotAClass
        !ResourcePaths.isValidInternalName(internalName) -> ResourceClass.Skip(SkipReason.INVALID_NAME)
        else -> ResourceClass.Candidate(internalName, release)
    }
}

/** The release named by [text] if it is a canonical decimal in 1..[FactsFormat.MAX_RELEASE]; `09` and `+9` are not. */
private fun parseRelease(text: String): Int? {
    val canonical = text.length in 1..MAX_RELEASE_DIGITS && text[0] in '1'..'9' && text.all { it in '0'..'9' }
    return if (canonical) text.toInt().takeIf { it <= FactsFormat.MAX_RELEASE } else null
}

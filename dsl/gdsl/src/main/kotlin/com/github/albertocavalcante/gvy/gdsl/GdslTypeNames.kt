// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gdsl

/**
 * Resolves a class literal's simple name the way Groovy's default imports would (`Closure` →
 * `groovy.lang.Closure`, `Map` → `java.util.Map`), from a fixed table. Nothing is loaded or looked
 * up reflectively, so a GDSL file full of unknown names costs one map lookup each; unknown or
 * qualified names are kept as written.
 */
internal object GdslTypeNames {
    private val DEFAULT_IMPORTS: Map<String, String> = buildMap {
        listOf(
            "Object", "String", "CharSequence", "Boolean", "Byte", "Character", "Short", "Integer", "Long",
            "Float", "Double", "Number", "Void", "Class", "Iterable", "Runnable", "Exception", "Throwable",
        ).forEach { put(it, "java.lang.$it") }
        listOf(
            "Map", "List", "Set", "Collection", "Iterator", "Date", "Optional", "SortedMap", "SortedSet",
            "LinkedHashMap", "HashMap", "ArrayList", "LinkedList", "HashSet", "TreeMap", "TreeSet",
        ).forEach { put(it, "java.util.$it") }
        listOf("File", "InputStream", "OutputStream", "Reader", "Writer").forEach { put(it, "java.io.$it") }
        listOf("URL", "URI").forEach { put(it, "java.net.$it") }
        listOf("Closure", "GString", "Script", "Binding", "Range", "MetaClass", "GroovyObject")
            .forEach { put(it, "groovy.lang.$it") }
        put("BigDecimal", "java.math.BigDecimal")
        put("BigInteger", "java.math.BigInteger")
    }

    fun resolve(name: String): String = DEFAULT_IMPORTS[name] ?: name
}

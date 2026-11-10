package com.github.albertocavalcante.gvy.gls.providers.rename

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode

/**
 * The text a file must contain to possibly use a rename target.
 *
 * Boundaries are lookarounds on identifier characters rather than `\b`, whose treatment of `$` and non-ASCII
 * letters differs between JDKs (`x.$foo()` and `x.café()` must match on every runtime).
 */
internal object NameMatcher {
    private const val IDENTIFIER_PART = "[\\p{javaJavaIdentifierPart}$]"
    private val ACCESSOR_PREFIXES = listOf("get", "set", "is")

    fun pattern(name: String, definition: ASTNode): Regex {
        val spellings = linkedSetOf(name)
        when (definition) {
            is PropertyNode, is FieldNode -> ACCESSOR_PREFIXES.forEach { prefix ->
                capitalizations(name).mapTo(spellings) { prefix + it }
            }
            is MethodNode -> propertyNamesOfAccessor(name).toCollection(spellings)
        }
        val alternatives = spellings.joinToString("|") { Regex.escape(it) }
        return Regex("(?<!$IDENTIFIER_PART)(?:$alternatives)(?!$IDENTIFIER_PART)")
    }

    /** Groovy capitalizes `label` as `Label`, but leaves `aB` alone (`getaB`); bean tools give `getAB`. */
    private fun capitalizations(property: String): Set<String> =
        linkedSetOf(property.replaceFirstChar { it.uppercaseChar() }, property)

    /** The properties a `getX`/`setX`/`isX` method may stand for: `getURL` is `URL` (Introspector) or `uRL`. */
    private fun propertyNamesOfAccessor(method: String): Set<String> {
        val prefix = ACCESSOR_PREFIXES.firstOrNull { method.startsWith(it) && method.length > it.length }
        val rest = prefix?.let { method.substring(it.length) }
        if (rest == null || !rest.first().isUpperCase()) return emptySet()
        val decapitalized = rest.replaceFirstChar { it.lowercaseChar() }
        return if (rest.length > 1 && rest[1].isUpperCase()) linkedSetOf(rest, decapitalized) else setOf(decapitalized)
    }
}

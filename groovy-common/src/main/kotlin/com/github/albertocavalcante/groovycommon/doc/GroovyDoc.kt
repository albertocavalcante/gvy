package com.github.albertocavalcante.groovycommon.doc

/**
 * Represents a parsed GroovyDoc comment.
 *
 * @deprecated Use `com.github.albertocavalcante.groovyparser.ast.groovydoc.Groovydoc` from parser/core instead.
 * This model is superseded by the richer Groovydoc model in parser/core.
 */
@Deprecated(
    message = "Use com.github.albertocavalcante.groovyparser.ast.groovydoc.Groovydoc from parser/core instead",
    replaceWith = ReplaceWith(
        "Groovydoc",
        "com.github.albertocavalcante.groovyparser.ast.groovydoc.Groovydoc",
    ),
    level = DeprecationLevel.WARNING,
)
data class GroovyDoc(
    val description: String = "",
    val params: List<ParamTag> = emptyList(),
    val returns: ReturnTag? = null,
    val throws: List<ThrowsTag> = emptyList(),
    val see: List<SeeTag> = emptyList(),
    val since: String? = null,
    val deprecated: String? = null,
    val author: String? = null,
)

data class ParamTag(val name: String, val description: String)

data class ReturnTag(val description: String)

data class ThrowsTag(val exception: String, val description: String)

data class SeeTag(val reference: String)

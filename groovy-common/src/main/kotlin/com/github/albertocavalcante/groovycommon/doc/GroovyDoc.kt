package com.github.albertocavalcante.groovycommon.doc

/**
 * Represents a parsed GroovyDoc comment.
 */
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

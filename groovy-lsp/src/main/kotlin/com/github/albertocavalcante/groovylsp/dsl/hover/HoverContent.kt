package com.github.albertocavalcante.groovylsp.dsl.hover

/**
 * Sealed class hierarchy for different types of hover content
 */
sealed class HoverContent {
    data class Text(val value: String) : HoverContent()
    data class Code(val value: String, val language: String = "groovy") : HoverContent()
    data class Markdown(val value: String) : HoverContent()
    data class Section(val title: String, val content: kotlin.collections.List<HoverContent>) : HoverContent()
    data class List(val items: kotlin.collections.List<String>) : HoverContent()
    data class KeyValue(val pairs: kotlin.collections.List<Pair<String, String>>) : HoverContent()
}

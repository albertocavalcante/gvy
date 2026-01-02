package com.github.albertocavalcante.groovylsp.markdown.dsl

/**
 * Sealed class hierarchy for different types of markdown content
 */
sealed class MarkdownContent {
    data class Text(val value: String) : MarkdownContent()
    data class Code(val value: String, val language: String = "groovy") : MarkdownContent()
    data class Markdown(val value: String) : MarkdownContent()
    data class Section(val title: String, val content: List<MarkdownContent>) : MarkdownContent()
    data class Header(val level: Int, val value: String) : MarkdownContent()
    data class List(val items: kotlin.collections.List<String>) : MarkdownContent()
    data class KeyValue(val pairs: kotlin.collections.List<Pair<String, String>>) : MarkdownContent()
    data class Table(
        val headers: kotlin.collections.List<String>,
        val rows: kotlin.collections.List<kotlin.collections.List<String>>
    ) : MarkdownContent()

    data class Link(val text: String, val url: String) : MarkdownContent()
}

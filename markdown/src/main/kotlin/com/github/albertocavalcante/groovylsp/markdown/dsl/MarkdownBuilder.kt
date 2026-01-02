package com.github.albertocavalcante.groovylsp.markdown.dsl

/**
 * Builder for creating markdown content using DSL
 */
class MarkdownBuilder {
    private val content = mutableListOf<MarkdownContent>()

    fun text(value: String) {
        content += MarkdownContent.Text(value)
    }

    fun header(level: Int = 1, value: String) {
        content += MarkdownContent.Header(level, value)
    }

    fun h1(value: String) = header(1, value)
    fun h2(value: String) = header(2, value)
    fun h3(value: String) = header(3, value)

    fun code(language: String = "groovy", value: String) {
        content += MarkdownContent.Code(value, language)
    }

    fun code(language: String = "groovy", block: () -> String) {
        content += MarkdownContent.Code(block(), language)
    }

    fun markdown(value: String) {
        content += MarkdownContent.Markdown(value)
    }

    fun section(title: String, block: MarkdownBuilder.() -> Unit) {
        val sectionContent = MarkdownBuilder().apply(block).build()
        content += MarkdownContent.Section(title, sectionContent)
    }

    fun list(vararg items: String) {
        content += MarkdownContent.List(items.toList())
    }

    fun list(items: List<String>) {
        content += MarkdownContent.List(items)
    }

    fun keyValue(vararg pairs: Pair<String, String>) {
        content += MarkdownContent.KeyValue(pairs.toList())
    }

    fun keyValue(pairs: List<Pair<String, String>>) {
        content += MarkdownContent.KeyValue(pairs)
    }

    fun table(headers: List<String>, rows: List<List<String>>) {
        content += MarkdownContent.Table(headers, rows)
    }

    fun link(text: String, url: String) {
        content += MarkdownContent.Link(text, url)
    }

    fun bold(value: String) = "**$value**"
    fun italic(value: String) = "_${value}_"
    fun inlineCode(value: String) = "`$value`"

    fun build(): List<MarkdownContent> = content.toList()

    fun render(): String = content.joinToString("\n\n") { it.render() }
}

/**
 * DSL function for building markdown content
 */
fun markdown(block: MarkdownBuilder.() -> Unit): String = MarkdownBuilder().apply(block).render()

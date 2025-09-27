package com.github.albertocavalcante.groovylsp.dsl.hover

import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind

/**
 * Builder for creating hover content using DSL
 */
class HoverBuilder {
    private val content = mutableListOf<HoverContent>()

    fun text(value: String) {
        content += HoverContent.Text(value)
    }

    fun code(language: String = "groovy", value: String) {
        content += HoverContent.Code(value, language)
    }

    fun code(language: String = "groovy", block: () -> String) {
        content += HoverContent.Code(block(), language)
    }

    fun markdown(value: String) {
        content += HoverContent.Markdown(value)
    }

    fun section(title: String, block: HoverBuilder.() -> Unit) {
        val sectionContent = HoverBuilder().apply(block).build()
        content += HoverContent.Section(title, sectionContent)
    }

    fun list(vararg items: String) {
        content += HoverContent.List(items.toList())
    }

    fun list(items: kotlin.collections.List<String>) {
        content += HoverContent.List(items)
    }

    fun keyValue(vararg pairs: Pair<String, String>) {
        content += HoverContent.KeyValue(pairs.toList())
    }

    fun keyValue(pairs: kotlin.collections.List<Pair<String, String>>) {
        content += HoverContent.KeyValue(pairs)
    }

    fun build(): kotlin.collections.List<HoverContent> = content.toList()

    fun render(): String = content.joinToString("\n\n") { it.render() }
}

/**
 * DSL function for building hover content
 */
fun hover(block: HoverBuilder.() -> Unit): Hover {
    val content = HoverBuilder().apply(block).render()

    val markupContent = MarkupContent().apply {
        kind = MarkupKind.MARKDOWN
        value = content
    }

    return Hover().apply {
        contents = org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(markupContent)
    }
}

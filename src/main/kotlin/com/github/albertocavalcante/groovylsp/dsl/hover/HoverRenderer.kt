package com.github.albertocavalcante.groovylsp.dsl.hover

/**
 * Renders HoverContent to markdown string
 */
fun HoverContent.render(): String = when (this) {
    is HoverContent.Text -> value
    is HoverContent.Code -> "```$language\n$value\n```"
    is HoverContent.Markdown -> value
    is HoverContent.Section -> {
        val contentStr = content.joinToString("\n\n") { item: HoverContent -> item.render() }
        "### $title\n\n$contentStr"
    }
    is HoverContent.List -> items.joinToString("\n") { "- $it" }
    is HoverContent.KeyValue -> pairs.joinToString("\n") { "**${it.first}**: ${it.second}" }
}

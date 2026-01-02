package com.github.albertocavalcante.groovylsp.markdown.dsl

/**
 * Renders MarkdownContent to markdown string
 */
fun MarkdownContent.render(): String = when (this) {
    is MarkdownContent.Text -> value
    is MarkdownContent.Code -> "```$language\n$value\n```"
    is MarkdownContent.Markdown -> value
    is MarkdownContent.Section -> {
        val contentStr = content.joinToString("\n\n") { item: MarkdownContent -> item.render() }
        "### $title\n\n$contentStr"
    }

    is MarkdownContent.Header -> "#".repeat(level.coerceAtLeast(1).coerceAtMost(6)) + " " + value
    is MarkdownContent.List -> items.joinToString("\n") { "- $it" }
    is MarkdownContent.KeyValue -> pairs.joinToString("\n") { "**${it.first}**: ${it.second}" }
    is MarkdownContent.Table -> buildString {
        append("| ${headers.joinToString(" | ")} |\n")
        append("| ${headers.joinToString(" | ") { "---" }} |\n")
        rows.forEach { row ->
            append("| ${row.joinToString(" | ")} |\n")
        }
    }

    is MarkdownContent.Link -> "[$text]($url)"
}

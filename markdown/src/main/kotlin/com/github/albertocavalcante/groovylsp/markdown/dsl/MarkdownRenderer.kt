package com.github.albertocavalcante.groovylsp.markdown.dsl

private const val MIN_HEADER_LEVEL = 1
private const val MAX_HEADER_LEVEL = 6
private const val SECTION_HEADER_LEVEL = 3

/**
 * Renders MarkdownContent to markdown string
 */
fun MarkdownContent.render(): String = when (this) {
    is MarkdownContent.Text -> value
    is MarkdownContent.Code -> "```$language\n$value\n```"
    is MarkdownContent.Markdown -> value
    is MarkdownContent.Section -> {
        val contentStr = content.joinToString("\n\n") { item: MarkdownContent -> item.render() }
        "${"#".repeat(SECTION_HEADER_LEVEL)} $title\n\n$contentStr"
    }

    is MarkdownContent.Header -> "#".repeat(level.coerceIn(MIN_HEADER_LEVEL, MAX_HEADER_LEVEL)) + " " + value
    is MarkdownContent.List -> items.joinToString("\n") { "- $it" }
    is MarkdownContent.KeyValue -> pairs.joinToString("\n") { "**${it.first}**: ${it.second}" }
    is MarkdownContent.Table -> buildString {
        append("| ${headers.joinToString(" | ")} |\n")
        append("| ${headers.joinToString(" | ") { "---" }} |\n")
        rows.forEach { row ->
            append("| ${row.joinToString(" | ")} |\n")
        }
    }.trim()

    is MarkdownContent.Link -> "[$text]($url)"
}

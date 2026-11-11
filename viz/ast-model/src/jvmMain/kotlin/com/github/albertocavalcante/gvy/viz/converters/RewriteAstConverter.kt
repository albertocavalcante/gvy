package com.github.albertocavalcante.gvy.viz.converters

import com.github.albertocavalcante.groovyparser.api.ParseUnit
import com.github.albertocavalcante.groovyparser.api.model.Range
import com.github.albertocavalcante.groovyparser.provider.RewriteParserProvider
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.RangeDto
import com.github.albertocavalcante.gvy.viz.model.RewriteAstNodeDto
import java.util.concurrent.atomic.AtomicInteger

/**
 * Converts OpenRewrite's parsed output to platform-agnostic DTOs.
 *
 * This converter uses OpenRewrite's lossless LST (via RewriteParserProvider) and creates
 * a serializable representation suitable for visualization.
 */
class RewriteAstConverter(private val parser: RewriteParserProvider) {

    /**
     * Parses source code and converts the result to an AstNodeDto.
     *
     * @param source The Groovy source code to parse.
     * @return The DTO representation, or null if parsing failed.
     */
    fun parse(source: String): AstNodeDto? {
        val parseUnit = parser.parse(source, null)
        if (!parseUnit.isSuccessful) return null

        val idGenerator = AtomicInteger(0)
        return buildTree(source, parseUnit, idGenerator)
    }

    /**
     * Build the AST tree from the parse unit's symbols.
     */
    private fun buildTree(source: String, parseUnit: ParseUnit, idGenerator: AtomicInteger): AstNodeDto {
        val children = parseUnit.symbols().map { symbol ->
            RewriteAstNodeDto(
                id = "node-${idGenerator.incrementAndGet()}",
                type = symbol.kind.name,
                range = symbol.range.toDtoRange(),
                children = emptyList(),
                properties = buildMap {
                    put("name", symbol.name)
                    symbol.containerName?.let { put("containerName", it) }
                    symbol.detail?.let { put("detail", it) }
                },
            )
        }

        val lines = source.lines()
        val lineCount = lines.size

        val startLine = 1
        val startColumn = 1
        val endLine = lines.size
        val endColumn = lines.last().length + 1

        return RewriteAstNodeDto(
            id = "node-0",
            type = "CompilationUnit",
            range = RangeDto(
                startLine = startLine,
                startColumn = startColumn,
                endLine = endLine,
                endColumn = endColumn,
            ),
            children = children,
            properties = mapOf(
                "lineCount" to lineCount.toString(),
            ),
        )
    }
}

internal fun Range.toDtoRange(): RangeDto? {
    if (start.line <= 0 || start.column <= 0 || end.line <= 0 || end.column <= 0 || end < start) return null
    return RangeDto(start.line, start.column, end.line, end.column)
}

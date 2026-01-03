package com.github.albertocavalcante.gvy.viz.converters

import com.github.albertocavalcante.groovyparser.api.ParseUnit
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
class RewriteAstConverter {

    private val idGenerator = AtomicInteger(0)
    private val parser = RewriteParserProvider()

    /**
     * Parses source code and converts the result to an AstNodeDto.
     *
     * @param source The Groovy source code to parse.
     * @return The DTO representation, or null if parsing failed.
     */
    fun parse(source: String): AstNodeDto? {
        idGenerator.set(0)
        val parseUnit = parser.parse(source, null)
        if (!parseUnit.isSuccessful) return null

        return buildTree(source, parseUnit)
    }

    /**
     * Build the AST tree from the parse unit's symbols.
     */
    private fun buildTree(source: String, parseUnit: ParseUnit): AstNodeDto {
        val children = parseUnit.symbols().map { symbol ->
            RewriteAstNodeDto(
                id = "node-${idGenerator.incrementAndGet()}",
                type = symbol.kind.name,
                range = RangeDto(
                    startLine = symbol.range.start.line,
                    startColumn = symbol.range.start.column,
                    endLine = symbol.range.end.line,
                    endColumn = symbol.range.end.column,
                ),
                children = emptyList(),
                properties = buildMap {
                    put("name", symbol.name)
                    symbol.containerName?.let { put("containerName", it) }
                    symbol.detail?.let { put("detail", it) }
                },
            )
        }

        val lines = source.lines()
        return RewriteAstNodeDto(
            id = "node-0",
            type = "CompilationUnit",
            range = RangeDto(
                startLine = 1,
                startColumn = 1,
                endLine = lines.size,
                endColumn = lines.lastOrNull()?.length ?: 1,
            ),
            children = children,
            properties = mapOf(
                "lineCount" to lines.size.toString(),
            ),
        )
    }
}

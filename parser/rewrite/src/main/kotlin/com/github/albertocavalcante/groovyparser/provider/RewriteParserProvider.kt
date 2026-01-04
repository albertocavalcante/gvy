package com.github.albertocavalcante.groovyparser.provider

import com.github.albertocavalcante.groovyparser.api.ParseUnit
import com.github.albertocavalcante.groovyparser.api.ParserCapabilities
import com.github.albertocavalcante.groovyparser.api.ParserProvider
import org.openrewrite.groovy.GroovyParser
import org.openrewrite.groovy.tree.G
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Parser provider using OpenRewrite's Groovy parser.
 *
 * This provides an alternative to the native parser with:
 * - Lossless LST (preserves whitespace and comments)
 * - Future refactoring support
 *
 * Trade-offs:
 * - Does NOT support error recovery (fails completely on syntax errors)
 * - Symbol resolution not exposed in initial implementation
 */
class RewriteParserProvider : ParserProvider {

    private val parser: GroovyParser = GroovyParser.builder().build()

    override val name: String = "rewrite"

    override val capabilities: ParserCapabilities = ParserCapabilities(
        supportsErrorRecovery = false,
        supportsCommentPreservation = true,
        supportsSymbolResolution = false,
        supportsRefactoring = true,
    )

    @Suppress("TooGenericExceptionCaught")
    override fun parse(source: String, path: Path?): ParseUnit = try {
        // TODO(#626): Consider ThreadLocal<GroovyParser> if contention becomes an issue
        val compilationUnit = synchronized(parser) {
            val sourceFiles = parser.parse(source).collect(Collectors.toList())
            sourceFiles.filterIsInstance<G.CompilationUnit>().firstOrNull()
        }
        if (compilationUnit != null) {
            RewriteParseUnit(source, path, compilationUnit)
        } else {
            RewriteParseUnit(source, path, null, parseError = "Parse failed")
        }
    } catch (e: Exception) {
        // OpenRewrite throws various exceptions for parse errors
        RewriteParseUnit(source, path, null, parseError = e.message)
    }
}

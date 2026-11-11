package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.ParserConfiguration
import com.github.albertocavalcante.groovyparser.provider.RewriteParserProvider
import com.github.albertocavalcante.gvy.viz.converters.CoreAstConverter
import com.github.albertocavalcante.gvy.viz.converters.NativeAstConverter
import com.github.albertocavalcante.gvy.viz.converters.RewriteAstConverter
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.GroovyRangeGuard
import com.github.albertocavalcante.gvy.viz.model.RangeDto
import com.github.albertocavalcante.nativeapi.ParseRequest
import java.io.File
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal data class ParseSnapshot(val source: String, val parser: ParserType, val path: String?)
internal data class ParseOutput(val tree: AstNodeDto?, val errors: List<CodeError>)

internal fun nativeUri(path: String?): URI = path?.let { File(it).toURI() } ?: URI("file:///temp.groovy")

/** Each invocation owns its parser, converter and mutable parser objects. */
internal fun parseSnapshot(snapshot: ParseSnapshot, cancelled: AtomicBoolean): ParseOutput = when (snapshot.parser) {
    ParserType.CORE -> parseCore(snapshot)
    ParserType.NATIVE -> parseNative(snapshot, cancelled)
    ParserType.REWRITE -> parseRewrite(snapshot)
}

private fun parseCore(snapshot: ParseSnapshot): ParseOutput {
    val result = GroovyParser(ParserConfiguration()).parse(snapshot.source)
    val guard = GroovyRangeGuard(snapshot.source)
    val errors = if (result.isSuccessful) {
        emptyList()
    } else {
        result.problems.map { problem ->
            val range = problem.range?.let {
                guard.validRange(RangeDto(it.begin.line, it.begin.column, it.end.line, it.end.column))
            }
            codeError(problem.message, range)
        }
    }
    val tree = result.result.map { CoreAstConverter().convert(it, snapshot.source) }.orElse(null)
    return ParseOutput(tree, errors)
}

private fun parseNative(snapshot: ParseSnapshot, cancelled: AtomicBoolean): ParseOutput {
    val result = GroovyParserFacade().parse(
        ParseRequest(uri = nativeUri(snapshot.path), content = snapshot.source, checkCancelled = {
            if (cancelled.get()) throw CancellationException("Parse superseded")
        }),
    )
    val guard = GroovyRangeGuard(snapshot.source)
    val errors = result.diagnostics.map { diagnostic ->
        val raw = diagnostic.range
        val range = guard.validRange(
            RangeDto(raw.start.line + 1, raw.start.character + 1, raw.end.line + 1, raw.end.character + 1),
        )
        codeError(diagnostic.message, range)
    }
    return ParseOutput(result.ast?.let { NativeAstConverter().convert(it, snapshot.source) }, errors)
}

private fun parseRewrite(snapshot: ParseSnapshot): ParseOutput {
    val tree = RewriteAstConverter(RewriteParserProvider()).parse(snapshot.source)
    val errors = if (tree == null) listOf(CodeError("Failed to parse with OpenRewrite parser")) else emptyList()
    return ParseOutput(tree, errors)
}

private fun codeError(message: String, range: RangeDto?): CodeError = if (range == null) {
    CodeError(message)
} else {
    CodeError(message, range.startLine, range.startColumn, range.endLine, range.endColumn)
}

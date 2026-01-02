package com.github.albertocavalcante.groovyparser.provider

import com.github.albertocavalcante.groovyparser.ParseResult
import com.github.albertocavalcante.groovyparser.ProblemSeverity
import com.github.albertocavalcante.groovyparser.api.ParseUnit
import com.github.albertocavalcante.groovyparser.api.model.Diagnostic
import com.github.albertocavalcante.groovyparser.api.model.NodeInfo
import com.github.albertocavalcante.groovyparser.api.model.Position
import com.github.albertocavalcante.groovyparser.api.model.Range
import com.github.albertocavalcante.groovyparser.api.model.Severity
import com.github.albertocavalcante.groovyparser.api.model.SymbolInfo
import com.github.albertocavalcante.groovyparser.api.model.SymbolKind
import com.github.albertocavalcante.groovyparser.api.model.TypeInfo
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import java.nio.file.Path

class CoreParseUnit(
    override val source: String,
    override val path: Path?,
    private val result: ParseResult<CompilationUnit>,
) : ParseUnit {

    override val isSuccessful: Boolean = result.isSuccessful

    override fun nodeAt(position: Position): NodeInfo? {
        val unit = result.result.orElse(null) ?: return null
        // TODO(#552): Implement position-based node lookup using visitor
        return null
    }

    override fun diagnostics(): List<Diagnostic> = result.problems.map { problem ->
        Diagnostic(
            severity = mapSeverity(problem.severity),
            message = problem.message,
            range = problem.range?.let { r ->
                Range(
                    start = Position(r.begin.line, r.begin.column),
                    end = Position(r.end.line, r.end.column),
                )
            } ?: Range.EMPTY,
            source = "groovy-parser-core",
        )
    }

    override fun symbols(): List<SymbolInfo> {
        val unit = result.result.orElse(null) ?: return emptyList()

        return unit.types.flatMap { type ->
            when (type) {
                is ClassDeclaration -> buildList {
                    add(
                        SymbolInfo(
                            name = type.name,
                            kind = if (type.isInterface) SymbolKind.INTERFACE else SymbolKind.CLASS,
                            range = extractRange(type),
                            containerName = unit.packageDeclaration.orElse(null)?.name,
                        ),
                    )
                    type.methods.mapTo(this) { method ->
                        SymbolInfo(
                            name = method.name,
                            kind = SymbolKind.METHOD,
                            range = extractRange(method),
                            containerName = type.name,
                        )
                    }
                    type.fields.mapTo(this) { field ->
                        SymbolInfo(
                            name = field.name,
                            kind = SymbolKind.FIELD,
                            range = extractRange(field),
                            containerName = type.name,
                        )
                    }
                }

                else -> emptyList()
            }
        }
    }

    override fun typeAt(position: Position): TypeInfo? {
        // TODO(#552): Implement type resolution using GroovySymbolResolver
        return null
    }

    private fun mapSeverity(severity: ProblemSeverity): Severity = when (severity) {
        ProblemSeverity.ERROR -> Severity.ERROR
        ProblemSeverity.WARNING -> Severity.WARNING
        ProblemSeverity.INFO -> Severity.INFO
        ProblemSeverity.HINT -> Severity.HINT
    }

    private fun extractRange(node: com.github.albertocavalcante.groovyparser.ast.Node): Range {
        val range = node.range ?: return Range.EMPTY
        return Range(
            start = Position(range.begin.line, range.begin.column),
            end = Position(range.end.line, range.end.column),
        )
    }
}

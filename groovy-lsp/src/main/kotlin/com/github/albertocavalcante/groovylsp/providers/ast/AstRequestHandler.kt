package com.github.albertocavalcante.groovylsp.providers.ast

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.gvy.viz.converters.CoreAstConverter
import com.github.albertocavalcante.gvy.viz.converters.NativeAstConverter
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.concurrent.CompletableFuture

data class AstParams(
    val uri: String,
    // "core" or "native"
    val parser: String,
)

data class AstResult(
    // JSON serialized AstNodeDto
    val ast: String,
    val parser: String,
)

class AstRequestHandler(
    private val compilationService: GroovyCompilationService,
    private val coreConverter: CoreAstConverter,
    private val nativeConverter: NativeAstConverter,
) {
    fun getAst(params: AstParams): CompletableFuture<AstResult> = CompletableFuture.supplyAsync {
        val uri = URI.create(params.uri)
        val parseResult = compilationService.getParseResult(uri)
            ?: throw IllegalArgumentException("No compilation result found for URI: ${params.uri}")

        val nativeAst = parseResult.ast
            ?: error("AST not available for URI: ${params.uri}")

        val astDto: AstNodeDto = when (params.parser.lowercase()) {
            "core" -> {
                val coreAst = GroovyParser.convertFromNative(nativeAst)
                coreConverter.convert(coreAst)
            }

            "native" -> {
                nativeConverter.convert(nativeAst)
            }

            else -> throw IllegalArgumentException("Unsupported parser type: ${params.parser}")
        }

        AstResult(
            ast = Json.encodeToString<AstNodeDto>(astDto),
            parser = params.parser,
        )
    }
}

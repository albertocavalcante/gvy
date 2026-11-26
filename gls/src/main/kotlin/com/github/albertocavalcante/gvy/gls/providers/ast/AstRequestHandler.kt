// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.ast

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotSource
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.viz.converters.CoreAstConverter
import com.github.albertocavalcante.gvy.viz.converters.NativeAstConverter
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.future
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

data class AstParams(val uri: String?, val parser: String?, val expectedSourceHash: String? = null)

data class AstResult(
    val ast: String,
    val parser: String,
    val documentVersion: Int? = null,
    val sourceHash: String? = null,
)

class AstRequestHandler(
    private val compilationService: GroovyCompilationService,
    private val coreConverter: CoreAstConverter,
    private val nativeConverter: NativeAstConverter,
    private val documents: DocumentProvider,
    private val coroutineScope: CoroutineScope,
) {
    private class Inspection(val source: SnapshotSource, val parser: String)

    fun getAst(request: AstParams?): CompletableFuture<AstResult> {
        val inspection = runCatching { capture(request) }
        return coroutineScope.future { inspect(inspection.getOrThrow()) }
    }

    private fun capture(request: AstParams?): Inspection {
        val params = request ?: fail(ResponseErrorCode.InvalidParams, "Missing AST request parameters")
        val uri = parseUri(params.uri)
        val requestedParser = params.parser ?: fail(ResponseErrorCode.InvalidParams, "Missing AST parser")
        val parser = requestedParser.lowercase()
        if (parser != "core" && parser != "native") {
            fail(ResponseErrorCode.InvalidParams, "Unsupported AST parser: $requestedParser")
        }
        val source = compilationService.captureSnapshotSource(uri)
        val snapshot = documents.getSnapshot(uri)
            ?: fail(ResponseErrorCode.RequestFailed, "Document is not open: ${params.uri}")
        if (params.expectedSourceHash != null && params.expectedSourceHash != snapshot.contentHash) {
            fail(ResponseErrorCode.ContentModified, "Document source hash does not match")
        }
        if (documents !== compilationService.documents || source.buffer?.snapshot !== snapshot) {
            fail(ResponseErrorCode.ContentModified, "Document changed during AST inspection")
        }
        return Inspection(source, requestedParser)
    }

    private suspend fun inspect(inspection: Inspection): AstResult {
        val source = inspection.source
        val snapshot = checkNotNull(source.buffer).snapshot
        val answer = compilationService.queryDerivedSnapshot(
            source,
            snapshot.content,
            inspectCompilerInputs = true,
        ) { held ->
            val nativeAst = requireCanonicalAst(held.parseResult, source.uri)
            val astDto: AstNodeDto = when (inspection.parser.lowercase()) {
                "core" -> coreConverter.convert(GroovyParser.convertFromNative(nativeAst), snapshot.content)
                else -> nativeConverter.convert(nativeAst, snapshot.content)
            }
            AstResult(
                ast = Json.encodeToString<AstNodeDto>(astDto),
                parser = inspection.parser,
                documentVersion = snapshot.version?.value,
                sourceHash = snapshot.contentHash,
            )
        }
        currentCoroutineContext().ensureActive()
        return when (answer) {
            is SnapshotQuery.Answer -> answer.value
            SnapshotQuery.Stale -> fail(ResponseErrorCode.ContentModified, "Inputs changed during AST inspection")
            SnapshotQuery.Missing -> fail(ResponseErrorCode.RequestFailed, "AST is unavailable: ${source.uri}")
            is SnapshotQuery.Failure -> throw answer.cause
        }
    }

    private fun requireCanonicalAst(parsed: ParseResult, uri: URI): ModuleNode {
        val ast = parsed.ast ?: fail(ResponseErrorCode.RequestFailed, "AST is unavailable: $uri")
        val source = parsed.sourceUnit
        // Phase labels survive caught failures; qualify the requested source's actual completion instead.
        // A recovered canonical AST can retain diagnostics from an earlier attempt.
        val completedCanonicalization =
            source.phase > Phases.CANONICALIZATION ||
                (source.phase == Phases.CANONICALIZATION && source.isPhaseComplete)
        val canonicalAstUnavailable =
            !completedCanonicalization ||
                source.name != uri.path ||
                ast !== source.ast ||
                ast.context !== source
        if (canonicalAstUnavailable) {
            fail(ResponseErrorCode.RequestFailed, "Canonical AST is unavailable: $uri")
        }
        return ast
    }

    private fun parseUri(value: String?): URI {
        val uriText = value ?: fail(ResponseErrorCode.InvalidParams, "Missing AST document URI")
        return try {
            URI.create(uriText).also { Path.of(it) }
        } catch (_: IllegalArgumentException) {
            fail(ResponseErrorCode.InvalidParams, "Invalid AST document URI")
        } catch (_: java.nio.file.FileSystemNotFoundException) {
            fail(ResponseErrorCode.InvalidParams, "Invalid AST document URI")
        }
    }

    private fun fail(code: ResponseErrorCode, message: String): Nothing =
        throw ResponseErrorException(ResponseError(code, message, null))
}

// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.ast

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.viz.converters.CoreAstConverter
import com.github.albertocavalcante.gvy.viz.converters.NativeAstConverter
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.future
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    fun getAst(request: AstParams?): CompletableFuture<AstResult> = coroutineScope.future {
        val params = request ?: fail(ResponseErrorCode.InvalidParams, "Missing AST request parameters")
        val uri = parseUri(params.uri)
        val requestedParser = params.parser ?: fail(ResponseErrorCode.InvalidParams, "Missing AST parser")
        val parser = requestedParser.lowercase()
        if (parser != "core" && parser != "native") {
            fail(ResponseErrorCode.InvalidParams, "Unsupported AST parser: $requestedParser")
        }
        val snapshot = documents.getSnapshot(uri)
            ?: fail(ResponseErrorCode.RequestFailed, "Document is not open: ${params.uri}")
        if (params.expectedSourceHash != null && params.expectedSourceHash != snapshot.contentHash) {
            fail(ResponseErrorCode.ContentModified, "Document source hash does not match")
        }
        currentCoroutineContext().ensureActive()
        val parsed = compilationService.compileTransient(uri, snapshot.content, Phases.CANONICALIZATION)
        currentCoroutineContext().ensureActive()
        requireCurrent(uri, snapshot)
        val nativeAst = parsed.ast ?: fail(ResponseErrorCode.RequestFailed, "AST is unavailable: ${params.uri}")
        val astDto: AstNodeDto = when (parser) {
            "core" -> coreConverter.convert(GroovyParser.convertFromNative(nativeAst), snapshot.content)
            else -> nativeConverter.convert(nativeAst, snapshot.content)
        }
        val result = AstResult(
            ast = Json.encodeToString<AstNodeDto>(astDto),
            parser = requestedParser,
            documentVersion = snapshot.version,
            sourceHash = snapshot.contentHash,
        )
        currentCoroutineContext().ensureActive()
        // This is the request's linearization point. Later edits must be rejected by the client using the
        // returned identity; completing a future under the document lock could run arbitrary callbacks there.
        requireCurrent(uri, snapshot)
        result
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

    private fun requireCurrent(uri: URI, snapshot: DocumentSnapshot) {
        if (documents.getSnapshot(uri) !== snapshot) {
            fail(ResponseErrorCode.ContentModified, "Document changed during AST inspection")
        }
    }

    private fun fail(code: ResponseErrorCode, message: String): Nothing =
        throw ResponseErrorException(ResponseError(code, message, null))
}

// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.project.JenkinsQueryFacts
import com.github.albertocavalcante.gvy.gls.project.JenkinsQuerySnapshot
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.utils.failingAsInternalError
import com.github.albertocavalcante.gvy.gls.utils.runSuspendCatching
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI

/** One editor request, including the producer of every recovery or dummy-inserted copy it actually uses. */
internal class SignatureCompletionRequest(
    val compilation: GroovyCompilationService,
    documents: DocumentProvider,
    val uri: URI,
    position: Position,
    completion: Boolean = false,
) {
    val position = Position(position.line, position.character)
    val source = compilation.captureSnapshotSource(uri)
    private val configuration = compilation.serverConfiguration
    private val engine = compilation.captureHoverEngine()
    private val classpathCurrent = compilation.classpathService.captureGeneration()
    private val jenkins = if (completion) JenkinsQuerySnapshot.capture(compilation, uri) else null
    private var jenkinsFacts: JenkinsQueryFacts? = null
    private lateinit var checkCancelled: () -> Unit
    private val inputs = mutableListOf<() -> Boolean>()
    private val publications = mutableListOf<() -> Boolean>()
    private val consultedMembers = mutableListOf<HoverMemberLookup>()

    init {
        if (documents !== compilation.documents ||
            source.buffer?.snapshot !== documents.getSnapshot(uri)
        ) {
            throw changed()
        }
    }

    private fun current(): Boolean =
        publications.all { it() } && jenkinsFacts?.publicationsCurrent?.invoke() != false &&
            classpathCurrent() && compilation.isHoverEngineCurrent(engine) &&
            compilation.serverConfiguration === configuration && consultedMembers.all { it.isDatabaseCurrent() } &&
            source.workerCurrent() && source.isCurrent()

    fun observePublication(current: () -> Boolean) {
        publications.add(current)
        if (!current()) throw changed()
    }

    fun observeInputs(current: (() -> Unit) -> Boolean) {
        inputs.add { current(checkCancelled) }
        if (!current(checkCancelled)) throw changed()
    }

    suspend fun completionJenkins(): JenkinsQueryFacts =
        jenkinsFacts ?: checkNotNull(jenkins).materialize(forHover = true)
            .also { jenkinsFacts = it }

    suspend fun <T> deliver(compute: suspend () -> T): T {
        val caller = currentCoroutineContext()
        checkCancelled = caller::ensureActive
        if (!caller.inputsCurrent(::current)) throw changed()
        val result = runSuspendCatching { compute() }
        val qualified = deliveryInputsCurrent(::current) {
            inputs.all { it() } && consultedMembers.all { it.isCurrent() } && jenkinsFacts?.isCurrent() != false
        }
        if (!qualified) throw changed()
        return result.getOrThrow()
    }

    suspend fun <T> original(missing: () -> T, compute: suspend (NativeQueryContext) -> T): T {
        val result = compilation.queryCapturedSnapshot(source, Phases.CANONICALIZATION, null) { held ->
            materialize(held, compute)
        }
        return unwrap(result, missing)
    }

    suspend fun <T> derived(content: String, compute: suspend (NativeQueryContext) -> T): T {
        val result = compilation.queryDerivedSnapshot(source, content) { held -> materialize(held, compute) }
        return unwrap(result) { error("A request-local parsing copy has no missing-source outcome") }
    }

    private suspend fun <T> materialize(held: CompiledDocumentSnapshot, compute: suspend (NativeQueryContext) -> T): T {
        val members = HoverMemberLookup.forRequest(held) { compilation.referenceReadiness(source) }
        consultedMembers.add(members)
        val context = NativeQueryContext(held, members)
        return withContext(context.binaryScope) { compute(context) }
    }

    private fun <T> unwrap(result: SnapshotQuery<T>, missing: () -> T): T = when (result) {
        is SnapshotQuery.Answer -> result.value
        SnapshotQuery.Missing -> missing()
        SnapshotQuery.Stale -> throw changed()
        is SnapshotQuery.Failure -> throw result.cause
    }

    private fun changed() = ResponseErrorException(
        ResponseError(ResponseErrorCode.ContentModified, "Inputs changed during request for $uri", null),
    )
}

/** Both entry capture and asynchronous computation report ordinary failures at the same protocol boundary. */
internal suspend fun <T> Result<SignatureCompletionRequest>.deliverSignatureOrCompletion(
    compute: suspend (SignatureCompletionRequest) -> T,
): T = failingAsInternalError("computing signature help or completions") {
    val request = getOrThrow()
    request.deliver { compute(request) }
}

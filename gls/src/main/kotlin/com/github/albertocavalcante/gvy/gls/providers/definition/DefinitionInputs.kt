// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition

import com.github.albertocavalcante.gvy.gls.compilation.CompilationEnsurer
import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.gls.compilation.deliveryInputsCurrent
import com.github.albertocavalcante.gvy.gls.indexing.CapturedWorkspaceReferences
import com.github.albertocavalcante.gvy.gls.project.JenkinsQueryFacts
import com.github.albertocavalcante.gvy.gls.providers.crossfile.NavigationRequest
import com.github.albertocavalcante.gvy.gls.providers.crossfile.navigationChanged
import com.github.albertocavalcante.gvy.gls.providers.crossfile.navigationUnavailable
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.bytecodeOnlyClassLoader
import kotlin.coroutines.CoroutineContext
import com.github.albertocavalcante.gvy.gls.utils.runSuspendCatching
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentSnapshot
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.inputDocumentUri
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ClassNode
import java.net.URI

/** Compiler and auxiliary inputs consulted by one definition lookup, including its range conversion. */
internal class DefinitionInputs(
    private val compilation: GroovyCompilationService,
    private val documents: DocumentProvider,
    private val request: NavigationRequest,
    val origin: CompiledDocumentSnapshot,
) {
    private val binaryLoader by lazy { bytecodeOnlyClassLoader(origin.parseResult.compilationUnit.classLoader) }
    val binaryContext: CoroutineContext get() = binaryLoader.context
    private val attachmentChecks = mutableListOf<suspend () -> Boolean>()
    private var graph: CapturedWorkspaceReferences? = null
    private val publications = mutableMapOf<URI, () -> Boolean>()
    private val targets = mutableMapOf<URI, SnapshotAcquisition.Available>()
    lateinit var jenkins: JenkinsQueryFacts
        private set

    suspend fun <T> query(compute: suspend () -> T): T {
        jenkins = checkNotNull(request.jenkins).materialize(forHover = true)
        val result = runSuspendCatching { compute() }
        val current = deliveryInputsCurrent({
            publications.values.all { it() } && targets.values.all { it.isCurrent() } &&
                graph?.isCurrent?.invoke() != false && jenkins.publicationsCurrent() && request.source.isCurrent()
        }) {
            targets.forEach { (uri, available) -> request.requireCurrentText(uri, available.snapshot.sourceText) }
            attachmentChecks.all { it() } && jenkins.isCurrent()
        }
        if (!current) throw navigationChanged("Definition inputs changed")
        return result.getOrThrow()
    }

    suspend fun index(): CapturedWorkspaceReferences {
        graph?.let { return it }
        CompilationEnsurer(documents, compilation).ensureAllCompiled()
        request.readiness.prepareOpenSources(compilation)
        if (request.source.buffer == null) compilation.ensureCompiled(request.uri)
        return request.readiness.captureMembersView().also { graph = it }
    }

    suspend fun qualify(uri: URI, indexed: SemanticDocumentSnapshot) {
        val held = target(uri)
        publications.getOrPut(uri) {
            request.readiness.qualifyDocument(uri, indexed, held)
                ?: throw navigationUnavailable("Unqualified definition index at $uri")
        }
    }

    fun binaryType(node: ClassNode?): Class<*>? {
        val selected = node?.redirect() ?: return null
        if (selected.isPrimaryClassNode || !selected.isResolved) return null
        return binaryLoader.read { selected.typeClass }
    }

    fun observeAttachment(check: suspend () -> Boolean) {
        attachmentChecks += check
    }

    fun declarationUri(type: ClassNode): URI? {
        val declaration = type.redirect()
        if (!declaration.isPrimaryClassNode) return null
        return declaration.module?.context?.inputDocumentUri() ?: request.uri.takeIf {
            origin.parseResult.ast?.classes.orEmpty().any { local -> local === declaration }
        }
    }

    fun targetUri(result: DefinitionResolver.DefinitionResult.Source): URI {
        val node = result.node
        val owner = (node as? ClassNode) ?: (node as? AnnotatedNode)?.declaringClass
        return owner?.let(::declarationUri) ?: result.uri
    }

    suspend fun textOf(uri: URI): String = target(uri).sourceText

    suspend fun target(uri: URI): CompiledDocumentSnapshot {
        if (uri == request.uri) return origin
        targets[uri]?.let { return it.snapshot }
        val mode = request.readiness.closedMode(uri) ?:
            if (uri in request.source.documents) ParseMode.WORKSPACE else ParseMode.MINIMAL
        val acquired = compilation.acquireSnapshot(uri, mode = mode)
        val available = when (acquired) {
            is SnapshotAcquisition.Available -> acquired
            SnapshotAcquisition.Stale -> throw navigationChanged("Definition target changed at $uri")
            is SnapshotAcquisition.Failure -> throw acquired.cause
            SnapshotAcquisition.Missing -> throw navigationUnavailable("Definition target unavailable at $uri")
        }
        if (origin.referenceProducer?.sameEnvironment(available.snapshot.referenceProducer) != true) {
            throw navigationUnavailable("Definition target has a different compiler environment at $uri")
        }
        targets[uri] = available
        return available.snapshot
    }
}

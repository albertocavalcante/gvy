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
import com.github.albertocavalcante.gvy.gls.providers.crossfile.requireNavigationSource
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.bytecodeOnlyClassLoader
import com.github.albertocavalcante.gvy.gls.utils.runSuspendCatching
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentSnapshot
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.inputDocumentUri
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ClassNode
import java.net.URI
import kotlin.coroutines.CoroutineContext

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
        val snapshot = acquireTarget(uri).snapshot
        if (origin.referenceProducer?.sameEnvironment(snapshot.referenceProducer) != true) {
            throw navigationUnavailable("Definition target has a different compiler environment at $uri")
        }
        return snapshot
    }

    /** Jenkins selects a vars file by path. Only syntax within that file supplies the line. */
    suspend fun jenkinsCallLine(uri: URI): Int {
        val snapshot = if (uri == request.uri) origin else acquireTarget(uri).snapshot
        val module = snapshot.parseResult.ast ?: throw navigationUnavailable("Missing Jenkins global source at $uri")
        return module.classes.filter { it.isScript }.flatMap { it.getDeclaredMethods("call") }
            .map { it.lineNumber }.filter { it > 0 }.minOrNull() ?: 1
    }

    private suspend fun acquireTarget(uri: URI): SnapshotAcquisition.Available {
        targets[uri]?.let { return it }
        val mode = request.readiness.closedMode(uri)
            ?: if (uri in request.source.documents) ParseMode.WORKSPACE else ParseMode.MINIMAL
        return compilation.acquireSnapshot(uri, mode = mode).requireNavigationSource(uri).also { targets[uri] = it }
    }
}

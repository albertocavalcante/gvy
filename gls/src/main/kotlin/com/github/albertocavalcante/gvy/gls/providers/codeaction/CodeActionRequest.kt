// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.common.UriPathConverter
import com.github.albertocavalcante.gvy.gls.compilation.ClassNameQuery
import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.gls.compilation.contentHash
import com.github.albertocavalcante.gvy.gls.compilation.deliveryInputsCurrent
import com.github.albertocavalcante.gvy.gls.compilation.inputsCurrent
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.gls.services.bytecodeOnlyClassLoader
import com.github.albertocavalcante.gvy.gls.utils.runSuspendCatching
import com.github.albertocavalcante.nativeapi.CompiledDocumentInput
import com.github.albertocavalcante.nativeapi.NativeSourceMembership
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.IdentityHashMap

/** Inputs captured at dispatch, plus the publications and source facts actually consulted by the providers. */
internal class CodeActionRequest(
    params: CodeActionParams,
    private val compilation: GroovyCompilationService,
    private val documents: DocumentProvider,
    private val supportsRemedies: () -> Boolean,
) {
    val params = copyCodeActionParams(params)
    private val uri = URI.create(this.params.textDocument.uri)
    private val source = compilation.captureSnapshotSource(uri)
    private val original = source.buffer?.snapshot
    private val classpathCurrent = compilation.classpathService.captureGeneration()
    val analysisRemedies = supportsRemedies()
    val fingerprint = compilation.workspaceManager.fingerprintFor(uri, source.workspace)
    private val checks = mutableListOf<() -> Boolean>()
    private val compilerSources = IdentityHashMap<ModuleNode, CompiledDocumentInput>()
    var consultedMembership: NativeSourceMembership? = null
        private set
    private var checkCancelled: () -> Unit = {}
    private val publications = mutableListOf<() -> Boolean>()
    var verdicts: SpockVerdictMemory.Access? = null

    init {
        if (compilation.documents !== documents || original !== documents.getSnapshot(uri)) throw changed()
    }

    fun findCandidates(name: String): List<String> {
        val (selected, current) = compilation.symbolIndexingService.captureClasses(ClassNameQuery.Exact(name))
        publications += current
        val index = compilation.getWorkspaceSymbolIndex()
        val shadows = index?.referenceShadows().orEmpty()
        val visible = selected.sourceHashes.filterKeys { it !in shadows }
        checks += {
            index === compilation.getWorkspaceSymbolIndex() && shadows == index?.referenceShadows().orEmpty() &&
                visible.all { (uri, hash) -> hasIndexedText(uri, hash) }
        }
        return selected.classes.filter { it.uri !in shadows }.map { it.fullyQualifiedName }
            .filter { it.contains('.') }.distinct()
    }

    private fun hasIndexedText(uri: URI, hash: String): Boolean {
        source.documents[uri]?.let { return it.contentHash == hash }
        val path = UriPathConverter.toPath(uri) ?: return false
        return try {
            Files.readAttributes(path, BasicFileAttributes::class.java).isRegularFile &&
                contentHash(Files.readString(path)) == hash
        } catch (_: NoSuchFileException) {
            false
        }
    }

    fun compilerInput(module: ModuleNode): CompiledDocumentInput = compilerSources.getOrPut(module) {
        val membership = NativeSourceMembership.of(module) ?: throw unavailable()
        if (consultedMembership != null && consultedMembership !== membership) throw unavailable()
        consultedMembership = membership
        CompiledDocumentInput.of(module) ?: throw unavailable()
    }

    private fun unavailable() = ResponseErrorException(
        ResponseError(ResponseErrorCode.RequestFailed, "Compiler source inputs could not be qualified", null),
    )

    private fun membershipCurrent(membership: NativeSourceMembership, checkCancelled: () -> Unit): Boolean =
        when (membership.check(checkCancelled)) {
            NativeSourceMembership.Status.CURRENT -> true
            NativeSourceMembership.Status.CHANGED -> false
            NativeSourceMembership.Status.UNAVAILABLE -> throw unavailable()
        }

    fun canRecall(membership: NativeSourceMembership): Boolean = observe {
        membershipCurrent(membership, checkCancelled)
    }

    fun <T> observe(read: () -> T): T = read().also { value -> checks += { read() == value } }

    suspend fun deliver(compute: (DocumentSnapshot, CompiledDocumentSnapshot) -> List<CodeAction>): List<CodeAction> {
        val caller = currentCoroutineContext()
        checkCancelled = caller::ensureActive
        requireCurrent(caller.inputsCurrent(::identitiesCurrent))
        val document = original ?: return emptyList()
        val held = acquire()
        requireCurrent(caller.inputsCurrent(held.isCurrent))
        val result = runSuspendCatching {
            bytecodeOnlyClassLoader(held.snapshot.parseResult.compilationUnit.classLoader).read {
                compute(document, held.snapshot)
            }
        }
        val qualified = deliveryInputsCurrent(::identitiesCurrent) {
            val consumed = runSuspendCatching { consumedInputsCurrent() }
            caller.inputsCurrent(held.isCurrent) && consumed.getOrThrow()
        }
        requireCurrent(qualified)
        val actions = result.getOrThrow()
        // IO and strategy callbacks have finished. Order the short write against document and ticket retirement.
        val ownership = source.dependencies.cacheService.ownership
        documents.withLifecycleLock {
            val committed = ownership.commit(source.ticket) {
                caller.ensureActive()
                requireCurrent(identitiesCurrent() && verdicts?.commit() != false)
            }
            requireCurrent(committed)
        }
        return actions
    }

    private suspend fun acquire(): SnapshotAcquisition.Available =
        when (val acquisition = compilation.acquireEditorSnapshot(source)) {
            is SnapshotAcquisition.Available -> acquisition
            is SnapshotAcquisition.Failure -> throw acquisition.cause
            SnapshotAcquisition.Missing, SnapshotAcquisition.Stale -> throw changed()
        }

    private fun requireCurrent(current: Boolean) {
        if (!current) throw changed()
    }

    private fun consumedInputsCurrent(): Boolean {
        val membership = consultedMembership
        if (membership != null && !membershipCurrent(membership, checkCancelled)) return false
        val sameSources = compilerSources.values.all { input ->
            input.matchesCurrentText(source.documents[input.uri]?.content, checkCancelled)
        }
        return sameSources && checks.all { it() } && supportsRemedies() == analysisRemedies &&
            compilation.workspaceManager.fingerprintFor(uri) == fingerprint
    }

    private fun identitiesCurrent(): Boolean = publications.all { it() } && classpathCurrent() &&
        source.workerCurrent() && source.isCurrent()

    private fun changed() = ResponseErrorException(
        ResponseError(ResponseErrorCode.ContentModified, "Inputs changed during code actions for $uri", null),
    )
}

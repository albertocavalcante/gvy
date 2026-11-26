// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.common.ast.stubSourceName
import com.github.albertocavalcante.nativeapi.CompiledDocumentInput
import com.github.albertocavalcante.nativeapi.NativeArtifactStamp
import com.github.albertocavalcante.nativeapi.NativeRetryInputs
import com.github.albertocavalcante.nativeapi.NativeSourceMembership
import com.github.albertocavalcante.nativeapi.ParseResult
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Receipts produced by this parser invocation, kept independently of its cache entry. */
internal class RequestParseInputs(parsed: ParseResult) {
    private val classpath = parsed.classpathInputs
    private val main: CompiledDocumentInput?
    private val companions: List<CompiledDocumentInput?>
    private val memberships: List<NativeSourceMembership?>
    private val mainModulePresent: Boolean
    private val excluded = parsed.outcome.excludedSources.map { it.source }
    private val recovered = parsed.outcome.fallback != null || parsed.outcome.stubbedClasses.isNotEmpty()
    private val retry = if (classpath == null) null else NativeRetryInputs.of(parsed)
    private val resolvedTypes = parsed.outcome.requestedPhase >= Phases.SEMANTIC_ANALYSIS

    init {
        // Older/synthetic workers without native receipts can describe an isolated in-memory parse.
        // They cannot establish physical source or dependency inputs for a workspace parse.
        val modules = if (classpath == null) emptyList() else parsed.compilationUnit.ast.modules
        val primary = modules.firstOrNull { it.context === parsed.sourceUnit }
        mainModulePresent = primary != null
        main = primary?.let(CompiledDocumentInput::of)
        val synthetic = parsed.outcome.stubbedClasses.mapTo(mutableSetOf(), ::stubSourceName)
        companions = modules.filterNot { it.context === parsed.sourceUnit || it.context?.name in synthetic }
            .map(CompiledDocumentInput::of)
        memberships = modules.map(NativeSourceMembership::of).distinct()
    }

    fun isCurrent(source: SnapshotSource, content: String, checkCancelled: () -> Unit): Boolean {
        requireIdentifiedWorkspace(source)
        checkCancelled()
        if (!discardedInputsCurrent(source, checkCancelled)) return false
        requirePrimarySource(source, content)
        return lookupsCurrent(checkCancelled) && companionsCurrent(source, checkCancelled) &&
            classpathCurrent(checkCancelled)
    }

    private fun requireIdentifiedWorkspace(source: SnapshotSource) {
        if (classpath != null) return
        val workspace = source.workspace
        val noPhysicalInputs = workspace.classpath.isEmpty() && workspace.sourceRoots.isEmpty() &&
            workspace.sources.isEmpty()
        if (!noPhysicalInputs || source.documents.keys.any { it != source.uri }) {
            unavailable("Compiler did not identify its physical inputs")
        }
    }

    private fun requirePrimarySource(source: SnapshotSource, content: String) {
        if (mainModulePresent && main == null) unavailable("Compiler did not identify its primary source")
        if (main != null && (main.uri != source.uri || !main.matchesText(content))) {
            unavailable("Requested source does not belong to this parse")
        }
    }

    private fun lookupsCurrent(checkCancelled: () -> Unit): Boolean = memberships.all { membership ->
        val observed = membership ?: unavailable("Compiler did not identify its source lookups")
        requireObservable(observed.check(checkCancelled), "Source lookup could not be verified")
    }

    private fun companionsCurrent(source: SnapshotSource, checkCancelled: () -> Unit): Boolean = companions.all {
        val input = it ?: unavailable("Compiler did not identify a companion source")
        input.matchesCurrentText(source.documents[input.uri]?.content, checkCancelled)
    }

    private fun classpathCurrent(checkCancelled: () -> Unit): Boolean {
        if (classpath == null || !resolvedTypes) return true
        return classpath.classpath.withIndex().all { (index, path) ->
            checkCancelled()
            val expected = classpath.classpathStamps[index] ?: unavailable("Unobserved classpath entry: $path")
            val actual = NativeArtifactStamp.observe(path, checkCancelled = checkCancelled)
                ?: unavailable("Classpath entry could not be verified: $path")
            actual == expected
        }
    }

    private fun discardedInputsCurrent(source: SnapshotSource, checkCancelled: () -> Unit): Boolean {
        if (excluded.isEmpty() && !recovered && retry?.hasDiscarded != true) return true
        val open = source.documents.keys.mapTo(mutableSetOf()) { it.toString() }
        val excludedDisk = excluded.filterNot(open::contains).mapTo(mutableSetOf(), ::excludedPath)
        val physicalSearch = classpath?.sourceRoots?.isNotEmpty() == true || classpath?.classpath?.isNotEmpty() == true
        val history = retry
        if ((excludedDisk.isNotEmpty() || (recovered && physicalSearch)) && history?.hasDiscarded != true) {
            unavailable("Earlier compiler inputs could not be verified")
        }
        if (history == null) return true
        val status = history.check(excludedDisk, { source.documents[it]?.content }, checkCancelled)
        return requireObservable(status, "Earlier compiler inputs could not be verified")
    }

    private fun excludedPath(name: String): Path = try {
        val path = Path.of(name)
        if (!path.isAbsolute) unavailable("Compiler did not identify an excluded disk source")
        path.normalize()
    } catch (_: InvalidPathException) {
        unavailable("Compiler did not identify an excluded disk source")
    }

    private fun requireObservable(status: NativeSourceMembership.Status, message: String): Boolean = when (status) {
        NativeSourceMembership.Status.CURRENT -> true
        NativeSourceMembership.Status.CHANGED -> false
        NativeSourceMembership.Status.UNAVAILABLE -> unavailable(message)
    }

    private fun unavailable(message: String): Nothing =
        throw ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, message, null))
}

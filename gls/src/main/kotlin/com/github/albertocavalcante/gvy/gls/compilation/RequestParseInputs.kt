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
        if (classpath == null) {
            val workspace = source.workspace
            val isolated = workspace.classpath.isEmpty() && workspace.sourceRoots.isEmpty() &&
                workspace.sources.isEmpty() && source.documents.keys.all { it == source.uri }
            if (!isolated) unavailable("Compiler did not identify its physical inputs")
        }

        checkCancelled()
        if (!discardedInputsCurrent(source, checkCancelled)) return false
        if (mainModulePresent && main == null) unavailable("Compiler did not identify its primary source")
        if (main != null && (main.uri != source.uri || !main.matchesText(content))) {
            unavailable("Requested source does not belong to this parse")
        }
        for (membership in memberships) {
            val observed = membership ?: unavailable("Compiler did not identify its source lookups")
            when (observed.check(checkCancelled)) {
                NativeSourceMembership.Status.CURRENT -> Unit
                NativeSourceMembership.Status.CHANGED -> return false
                NativeSourceMembership.Status.UNAVAILABLE -> unavailable("Source lookup could not be verified")
            }
        }
        for (companion in companions) {
            val input = companion ?: unavailable("Compiler did not identify a companion source")
            if (!input.matchesCurrentText(source.documents[input.uri]?.content, checkCancelled)) return false
        }
        if (classpath != null && resolvedTypes) {
            for ((index, path) in classpath.classpath.withIndex()) {
                checkCancelled()
                val expected = classpath.classpathStamps[index] ?: unavailable("Unobserved classpath entry: $path")
                val actual = NativeArtifactStamp.observe(path, checkCancelled = checkCancelled)
                    ?: unavailable("Classpath entry could not be verified: $path")
                if (actual != expected) return false
            }
        }
        return true
    }

    private fun discardedInputsCurrent(source: SnapshotSource, checkCancelled: () -> Unit): Boolean {
        if (excluded.isEmpty() && !recovered && retry?.hasDiscarded != true) return true
        val open = source.documents.keys.mapTo(mutableSetOf()) { it.toString() }
        val excludedDisk = excluded.filterNot(open::contains).mapTo(mutableSetOf()) { name ->
            try {
                val path = Path.of(name)
                if (!path.isAbsolute) unavailable("Compiler did not identify an excluded disk source")
                path.normalize()
            } catch (_: InvalidPathException) {
                unavailable("Compiler did not identify an excluded disk source")
            }
        }
        val physicalSearch = classpath?.sourceRoots?.isNotEmpty() == true || classpath?.classpath?.isNotEmpty() == true
        val history = retry
        if ((excludedDisk.isNotEmpty() || (recovered && physicalSearch)) && history?.hasDiscarded != true) {
            unavailable("Earlier compiler inputs could not be verified")
        }
        if (history == null) return true
        val status = history.check(excludedDisk, { source.documents[it]?.content }, checkCancelled)
        return when (status) {
            NativeSourceMembership.Status.CURRENT -> true
            NativeSourceMembership.Status.CHANGED -> false
            NativeSourceMembership.Status.UNAVAILABLE -> unavailable("Earlier compiler inputs could not be verified")
        }
    }

    private fun unavailable(message: String): Nothing =
        throw ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, message, null))
}

// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.common.ast.stubSourceName
import com.github.albertocavalcante.nativeapi.CompiledDocumentInput
import com.github.albertocavalcante.nativeapi.NativeArtifactStamp
import com.github.albertocavalcante.nativeapi.NativeRetryInputs
import com.github.albertocavalcante.nativeapi.NativeSourceMembership
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import com.github.albertocavalcante.nativeapi.matchesPrimaryInput
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Binds the selected worker's result to its own primary text, native inputs and producer freshness. */
internal class RequestParseInputs(
    request: ParseRequest,
    parsed: ParseResult,
    isolatedWorker: Boolean,
    locatedDisk: Boolean = false,
    private val producerCurrent: (SnapshotSource) -> Boolean,
) {
    private val uri = request.uri
    private val content = request.content
    private val classpath = parsed.classpathInputs
    private val retry = NativeRetryInputs.of(parsed)
    private val resolvedTypes = request.compilePhase >= Phases.SEMANTIC_ANALYSIS
    private val excluded = parsed.outcome.excludedSources.map { it.source }
    private val recovered = parsed.outcome.fallback != null || parsed.outcome.stubbedClasses.isNotEmpty()
    private val isolated = isolatedWorker && !locatedDisk && request.classpath.isEmpty() &&
        request.sourceRoots.isEmpty() && request.workspaceSources.isEmpty() && request.openSources.isEmpty()
    private val matchingClasspath = classpath == null ||
        (classpath.classpath == request.classpath && classpath.sourceRoots == request.sourceRoots)
    private val primaryMatches = parsed.sourceUnit.matchesPrimaryInput(uri, content) &&
        parsed.compilationUnit.iterator().asSequence().any { it === parsed.sourceUnit }
    private val identifiedModules: Boolean
    val ownsSymbolAst: Boolean

    init {
        val modules = parsed.compilationUnit.ast.modules
        val primary = modules.firstOrNull { it.context === parsed.sourceUnit }
        ownsSymbolAst = primaryMatches && primary != null && parsed.ast === primary && primary === parsed.sourceUnit.ast
        val synthetic = parsed.outcome.stubbedClasses.mapTo(mutableSetOf(), ::stubSourceName)
        identifiedModules = if (retry == null && classpath == null) {
            modules.all { it === primary }
        } else {
            modules.all { module ->
                val identified = module.context?.name in synthetic || CompiledDocumentInput.of(module) != null
                identified && retry?.owns(module) == true
            }
        }
    }

    fun isCurrent(source: SnapshotSource, checkCancelled: () -> Unit): Boolean {
        checkCancelled()
        if (source.uri != uri || !producerCurrent(source)) return false
        if (classpath == null && (!isolated || retry?.isolated(uri) == false)) {
            unavailableParseInputs("Compiler did not identify its physical inputs")
        }
        if (!matchingClasspath || !primaryMatches || !identifiedModules) {
            unavailableParseInputs("Compiler sources or classpath do not belong to this invocation")
        }
        return attemptsCurrent(source, checkCancelled) && classpathCurrent(checkCancelled)
    }

    private fun classpathCurrent(checkCancelled: () -> Unit): Boolean {
        if (classpath == null || !resolvedTypes) return true
        return classpath.classpath.withIndex().all { (index, path) ->
            checkCancelled()
            val expected = classpath.classpathStamps[index]
                ?: unavailableParseInputs("Unobserved classpath entry: $path")
            val actual = NativeArtifactStamp.observe(path, checkCancelled = checkCancelled)
                ?: unavailableParseInputs("Classpath entry could not be verified: $path")
            actual == expected
        }
    }

    private fun attemptsCurrent(source: SnapshotSource, checkCancelled: () -> Unit): Boolean {
        val excludedDisk = if (excluded.isEmpty()) {
            emptySet()
        } else {
            val open = source.documents.keys.mapTo(mutableSetOf()) { it.toString() }
            excluded.filterNot(open::contains).mapTo(mutableSetOf(), ::excludedPath)
        }
        val physicalSearch = classpath?.sourceRoots?.isNotEmpty() == true || classpath?.classpath?.isNotEmpty() == true
        val history = retry
        val requiresHistory = excludedDisk.isNotEmpty() || (recovered && physicalSearch)
        if (requiresHistory && history?.hasDiscarded != true) {
            unavailableParseInputs("Earlier compiler inputs could not be verified")
        }
        if (history == null) {
            if (classpath != null) unavailableParseInputs("Compiler did not identify its source reads")
            return true
        }
        val status = history.check(
            excludedDisk,
            { if (it == uri) content else source.documents[it]?.content },
            checkCancelled,
        )
        return requireObservable(status, "Compiler source reads could not be verified")
    }

    private fun excludedPath(name: String): Path = try {
        val path = Path.of(name)
        if (!path.isAbsolute) unavailableParseInputs("Compiler did not identify an excluded disk source")
        path.normalize()
    } catch (_: InvalidPathException) {
        unavailableParseInputs("Compiler did not identify an excluded disk source")
    }

    private fun requireObservable(status: NativeSourceMembership.Status, message: String): Boolean = when (status) {
        NativeSourceMembership.Status.CURRENT -> true
        NativeSourceMembership.Status.CHANGED -> false
        NativeSourceMembership.Status.UNAVAILABLE -> unavailableParseInputs(message)
    }
}

internal fun unavailableParseInputs(message: String): Nothing =
    throw ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, message, null))

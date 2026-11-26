// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.project

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ModeResolver
import com.github.albertocavalcante.gvy.jenkins.GlobalVariable
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI
import java.nio.file.Path

/** Entry capture performs no workspace file I/O. A query reads only the facts its feature uses. */
internal class JenkinsQuerySnapshot private constructor(
    private val compilation: GroovyCompilationService,
    private val uri: URI,
) {
    private val capability = compilation.workspaceManager.getJenkinsCapabilities()
    private val strategy = capability as? JenkinsProjectStrategy
    private val manager = strategy?.queryManager
    private val gdslCurrent = manager?.captureGdsl()
    private val mode = compilation.serverConfiguration.groovyMode
    private val isFile = capability?.isJenkinsFile(uri) == true

    fun isCurrent(): Boolean = (capability?.isJenkinsFile(uri) == true) == isFile &&
        authoritiesCurrent()

    fun authoritiesCurrent(): Boolean = compilation.workspaceManager.getJenkinsCapabilities() === capability &&
        strategy?.queryManager === manager && compilation.serverConfiguration.groovyMode == mode

    private fun variableNames(): Set<String> = manager?.getGlobalVariableNames()
        ?: capability?.getGlobalVariables().orEmpty().mapTo(mutableSetOf()) { it.name }

    suspend fun materialize(forHover: Boolean = false): JenkinsQueryFacts = withContext(Dispatchers.IO) {
        if (!isCurrent()) throw staleJenkins()
        val globals = lazy { capability?.getGlobalVariables().orEmpty().toList() }
        val native = lazy { manager?.captureMetadata(uri) }
        val metadata = lazy { native.value?.metadata ?: capability?.getAllMetadata(uri) }
        val names = if (!forHover && isFile) variableNames() else emptySet()
        val frozen = capability?.let { original ->
            object : JenkinsCapabilities by original {
                override fun isJenkinsFile(uri: URI) = isFile
                override fun getGlobalVariables(): List<GlobalVariable> = globals.value
                override fun getAllMetadata(uri: URI): MergedJenkinsMetadata? = metadata.value
            }
        }
        fun publicationsCurrent(): Boolean {
            val selection = if (native.isInitialized()) native.value else null
            return authoritiesCurrent() &&
                (selection == null || (selection.isCurrent() && gdslCurrent?.invoke() == true))
        }
        fun inputsCurrent(): Boolean {
            if (!isCurrent()) return false
            val original = if (native.isInitialized()) native.value else null
            if (metadata.isInitialized() && original == null && capability?.getAllMetadata(uri) != metadata.value) {
                return false
            }
            if (globals.isInitialized() && capability?.getGlobalVariables().orEmpty() != globals.value) return false
            if (!forHover && isFile && variableNames() != names) return false
            // The native publication checks are identity reads. Run them after custom capability callbacks and IO.
            return publicationsCurrent()
        }
        JenkinsQueryFacts(
            isFile,
            names,
            frozen,
            ModeResolver(mode, frozen),
            consultedClasspath = { if (native.isInitialized()) native.value?.classpath else null },
            publicationsCurrent = ::publicationsCurrent,
            current = ::inputsCurrent,
        )
    }

    companion object {
        fun capture(compilation: GroovyCompilationService, uri: URI) = JenkinsQuerySnapshot(compilation, uri)
    }
}

internal class JenkinsQueryFacts(
    val isJenkinsFile: Boolean,
    val variables: Set<String>,
    val capability: JenkinsCapabilities?,
    val mode: ModeResolver,
    private val consultedClasspath: () -> List<Path>?,
    val publicationsCurrent: () -> Boolean,
    private val current: () -> Boolean,
) {
    val classpath: List<Path>? get() = consultedClasspath()

    suspend fun isCurrent(): Boolean {
        // Carry the synchronous outcome across the dispatcher hop. Coroutine stack recovery can copy a thrown Error,
        // and prompt cancellation on return from IO must not hide a fatal error the callback already raised.
        var checked: Result<Boolean>? = null
        try {
            withContext(Dispatchers.IO) { checked = runCatching(current) }
        } catch (cancelled: CancellationException) {
            val failure = checked?.exceptionOrNull()
            if (failure is Error) throw failure
            throw cancelled
        }
        return checkNotNull(checked).getOrThrow()
    }
}

private fun staleJenkins() = ResponseErrorException(
    ResponseError(ResponseErrorCode.ContentModified, "Jenkins inputs changed during the request", null),
)

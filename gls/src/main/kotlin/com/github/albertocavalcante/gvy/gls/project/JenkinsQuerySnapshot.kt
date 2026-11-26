// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.project

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ModeResolver
import com.github.albertocavalcante.gvy.gls.utils.withContextPreservingFatal
import com.github.albertocavalcante.gvy.jenkins.GlobalVariable
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
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
        val reads = ConsultedInputs(forHover)
        val frozen = reads.frozenCapability()
        JenkinsQueryFacts(isFile, reads.names, frozen, ModeResolver(mode, frozen), reads)
    }

    private inner class ConsultedInputs(private val forHover: Boolean) : JenkinsInputChecks {
        private val globals = lazy { capability?.getGlobalVariables().orEmpty().toList() }
        private val native = lazy { manager?.captureMetadata(uri) }
        private val metadata = lazy { native.value?.metadata ?: capability?.getAllMetadata(uri) }
        val names = if (!forHover && isFile) variableNames() else emptySet()

        private val consultedMetadata get() = if (native.isInitialized()) native.value else null
        override val classpath: List<Path>? get() = consultedMetadata?.classpath

        fun frozenCapability(): JenkinsCapabilities? = capability?.let { original ->
            object : JenkinsCapabilities by original {
                override fun isJenkinsFile(uri: URI) = isFile
                override fun getGlobalVariables(): List<GlobalVariable> = globals.value
                override fun getAllMetadata(uri: URI): MergedJenkinsMetadata? = metadata.value
            }
        }

        override fun publicationsCurrent(): Boolean {
            val selection = consultedMetadata
            return authoritiesCurrent() &&
                (selection == null || (selection.isCurrent() && gdslCurrent?.invoke() == true))
        }

        private fun metadataCurrent(): Boolean = !metadata.isInitialized() ||
            consultedMetadata != null ||
            capability?.getAllMetadata(uri) == metadata.value

        private fun globalsCurrent(): Boolean = !globals.isInitialized() ||
            capability?.getGlobalVariables().orEmpty() == globals.value

        // Run the native publication checks after custom capability callbacks and IO.
        override fun inputsCurrent(): Boolean = isCurrent() &&
            metadataCurrent() &&
            globalsCurrent() &&
            (forHover || !isFile || variableNames() == names) &&
            publicationsCurrent()
    }

    companion object {
        fun capture(compilation: GroovyCompilationService, uri: URI) = JenkinsQuerySnapshot(compilation, uri)
    }
}

/** Qualification for the values a single query actually read. */
internal interface JenkinsInputChecks {
    val classpath: List<Path>?
    fun publicationsCurrent(): Boolean
    fun inputsCurrent(): Boolean
}

internal class JenkinsQueryFacts(
    val isJenkinsFile: Boolean,
    val variables: Set<String>,
    val capability: JenkinsCapabilities?,
    val mode: ModeResolver,
    private val checks: JenkinsInputChecks,
) {
    val classpath: List<Path>? get() = checks.classpath
    val publicationsCurrent: () -> Boolean = checks::publicationsCurrent

    suspend fun isCurrent(): Boolean = withContextPreservingFatal(Dispatchers.IO) { checks.inputsCurrent() }
}

private fun staleJenkins() = ResponseErrorException(
    ResponseError(ResponseErrorCode.ContentModified, "Jenkins inputs changed during the request", null),
)

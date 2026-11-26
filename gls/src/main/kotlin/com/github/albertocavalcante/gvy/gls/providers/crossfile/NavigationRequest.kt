// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.crossfile

import com.github.albertocavalcante.gvy.common.UriPathConverter
import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.ReferenceReadiness
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotSource
import com.github.albertocavalcante.gvy.gls.project.JenkinsQuerySnapshot
import com.github.albertocavalcante.gvy.gls.utils.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/** Mutable protocol fields and input authorities are captured before the request coroutine is scheduled. */
internal class NavigationRequest(
    val source: SnapshotSource,
    val position: Position,
    val readiness: ReferenceReadiness,
    val jenkins: JenkinsQuerySnapshot? = null,
    val links: Boolean = false,
) {
    val uri get() = source.uri
    private val mode = readiness.closedMode(uri)

    suspend fun <T> query(
        compilation: GroovyCompilationService,
        missing: T,
        compute: suspend (CompiledDocumentSnapshot) -> T,
    ): T {
        val answer = compilation.queryCapturedSnapshot(source, Phases.CANONICALIZATION, mode) { held ->
            val result = runSuspendCatching { compute(held) }
            requireCurrentText(uri, held.sourceText)
            result.getOrThrow()
        }
        return when (answer) {
            is SnapshotQuery.Answer -> answer.value
            SnapshotQuery.Missing -> missing
            SnapshotQuery.Stale -> throw navigationChanged(uri.toString())
            is SnapshotQuery.Failure -> throw answer.cause
        }
    }

    suspend fun requireCurrentText(uri: URI, expected: String) {
        if (uri in source.documents) return
        val path = UriPathConverter.toPath(uri) ?: throw navigationUnavailable("No source path for $uri")
        val matches = withContext(Dispatchers.IO) {
            try {
                Files.readString(path) == expected
            } catch (_: NoSuchFileException) {
                false
            }
        }
        if (!matches) throw navigationChanged("Disk source changed at $uri")
    }
}

internal fun navigationChanged(reason: String) =
    ResponseErrorException(ResponseError(ResponseErrorCode.ContentModified, reason, null))

internal fun navigationUnavailable(reason: String) =
    ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, reason, null))

internal fun SnapshotAcquisition.requireNavigationSource(uri: URI): SnapshotAcquisition.Available = when (this) {
    is SnapshotAcquisition.Available -> this
    is SnapshotAcquisition.Failure -> throw cause
    SnapshotAcquisition.Stale, SnapshotAcquisition.Missing -> throw if (this === SnapshotAcquisition.Stale) {
        navigationChanged("Navigation source changed at $uri")
    } else {
        navigationUnavailable("Navigation source unavailable at $uri")
    }
}

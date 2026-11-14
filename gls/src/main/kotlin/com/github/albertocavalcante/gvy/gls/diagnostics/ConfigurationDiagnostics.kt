// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.contentHash
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

private const val MAX_CONFIGURATION_BYTES = 256 * 1024

internal data class ConfigurationCandidate(
    val uri: URI,
    val hash: String,
    val snapshot: DocumentSnapshot?,
    val diagnostic: Diagnostic,
)

/** Convert a source notice's related location to a configuration notice only for the text that was parsed. */
internal fun configurationCandidates(
    documentProvider: DocumentProvider,
    sourceUri: URI,
    diagnostics: List<Diagnostic>,
): List<ConfigurationCandidate> = diagnostics.mapNotNull { diagnostic ->
    val data = diagnostic.data as? Map<*, *> ?: return@mapNotNull null
    val uriText = data["configurationUri"] as? String ?: return@mapNotNull null
    val hash = data["configurationHash"] as? String ?: return@mapNotNull null
    val uri = try {
        URI.create(uriText)
    } catch (_: IllegalArgumentException) {
        return@mapNotNull null
    }
    val location = diagnostic.relatedInformation?.firstOrNull()?.location
    if (uri.scheme != "file") return@mapNotNull null
    if (location == null && sourceUri != uri) return@mapNotNull null
    if (location != null && location.uri != uriText) return@mapNotNull null
    val snapshot = documentProvider.getSnapshot(uri)
    val observedHash = snapshot?.contentHash ?: diskConfigurationHash(uri)
    if (observedHash != hash) return@mapNotNull null
    val notice = Diagnostic(
        location?.range ?: diagnostic.range,
        diagnostic.message,
        diagnostic.severity,
        diagnostic.source,
    ).apply {
        code = diagnostic.code
        this.data = diagnostic.data
    }
    ConfigurationCandidate(uri, hash, snapshot, notice)
}

/** Caller holds the document lifecycle lock so open configuration snapshots cannot change. */
internal fun currentConfigurationCandidates(
    documentProvider: DocumentProvider,
    candidates: List<ConfigurationCandidate>,
): List<ConfigurationCandidate> = candidates.filter { candidate ->
    val current = documentProvider.getSnapshot(candidate.uri)
    current === candidate.snapshot && (current?.contentHash ?: diskConfigurationHash(candidate.uri)) == candidate.hash
}

internal fun filterConfigurationDiagnostics(
    diagnostics: List<Diagnostic>,
    valid: List<ConfigurationCandidate>,
): List<Diagnostic> {
    val validKeys = valid.map { it.uri.toString() to it.hash }.toSet()
    return diagnostics.filter { diagnostic ->
        val data = diagnostic.data as? Map<*, *>
        val uri = data?.get("configurationUri") as? String
        val hash = data?.get("configurationHash") as? String
        uri == null || hash == null || (uri to hash) in validKeys
    }
}

internal fun diskConfigurationHash(uri: URI, reader: (Path) -> ByteArray = ::readBoundedConfiguration): String? = try {
    val bytes = reader(Path.of(uri))
    if (bytes.size > MAX_CONFIGURATION_BYTES) null else contentHash(bytes.toString(Charsets.UTF_8))
} catch (_: IOException) {
    null
} catch (_: IllegalArgumentException) {
    null
} catch (_: SecurityException) {
    null
}

private fun readBoundedConfiguration(path: Path): ByteArray =
    Files.newInputStream(path).use { it.readNBytes(MAX_CONFIGURATION_BYTES + 1) }

internal fun Diagnostic.isConfigurationNoticeFor(uri: URI? = null): Boolean {
    val data = data as? Map<*, *> ?: return false
    val target = data["configurationUri"] as? String ?: return false
    return uri == null || target == uri.toString() ||
        relatedInformation.orEmpty().any { it.location.uri == uri.toString() }
}

internal data class ConfigurationPublicationState(
    val ownDiagnostics: MutableMap<URI, List<Diagnostic>>,
    val configurationOwners: ConfigurationDiagnosticOwners,
)

/** Caller holds the publication lock. */
internal fun publishCombined(
    recipient: LanguageClient?,
    uri: URI,
    version: Int?,
    maxProblems: Int,
    state: ConfigurationPublicationState,
) {
    if (recipient == null) return
    val all = (state.ownDiagnostics[uri].orEmpty() + state.configurationOwners.forUri(uri)).distinctBy {
        Triple(it.range, it.code, it.message)
    }
    recipient.publishDiagnostics(
        PublishDiagnosticsParams(uri.toString(), all.take(maxProblems.coerceAtLeast(0)), version),
    )
}

/** Caller holds the document lifecycle and publication locks. */
internal fun clearConfigurationNotices(
    recipient: LanguageClient?,
    target: URI?,
    documentProvider: DocumentProvider,
    maxProblems: Int,
    state: ConfigurationPublicationState,
) {
    val affected = if (target == null) {
        state.configurationOwners.clear().toMutableSet()
    } else {
        mutableSetOf<URI>().apply { if (state.configurationOwners.clearTarget(target)) add(target) }
    }
    state.ownDiagnostics.entries.toList().forEach { (uri, diagnostics) ->
        val retained = diagnostics.filterNot { it.isConfigurationNoticeFor(target) }
        if (retained.size != diagnostics.size) {
            state.ownDiagnostics[uri] = retained
            affected.add(uri)
        }
    }
    affected.forEach { publishCombined(recipient, it, documentProvider.getSnapshot(it)?.version, maxProblems, state) }
}

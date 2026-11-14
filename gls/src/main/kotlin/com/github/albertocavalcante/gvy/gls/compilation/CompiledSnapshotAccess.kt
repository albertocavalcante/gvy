// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.OpenBuffer
import java.net.URI

/** Captures existing buffer, cache and retirement authorities without introducing another generation. */
internal class CompiledSnapshotAccess(
    private val cache: CompilationCacheService,
    private val workspace: WorkspaceManager,
    private val documents: DocumentProvider?,
) {
    /** Atomic raw receipt pair with primary/cache checks, without physical source admission. */
    fun captureCached(uri: URI): CompiledDocumentSnapshot? {
        val ticket = cache.ownership.begin(uri)
        val fingerprint = workspace.fingerprintFor(uri)
        val buffer = documents?.getBuffer(uri)
        val cached = cache.getCachedRecord(uri, fingerprint) ?: return null
        if (cached.admissionFingerprint == null) return null
        if (buffer != null && buffer.snapshot.content != cached.content) return null
        return CompiledDocumentSnapshot(
            sourceText = cached.content,
            parseResult = cached.parseResult,
            buffer = buffer,
            configurationFingerprint = fingerprint,
            ownershipTicket = ticket,
            admissionFingerprint = cached.admissionFingerprint,
        )
            .takeIf { isCurrent(uri, it) }
    }

    fun isCurrent(uri: URI, snapshot: CompiledDocumentSnapshot): Boolean {
        val ticket = snapshot.ownershipTicket ?: return false
        val fingerprint = snapshot.configurationFingerprint ?: return false
        if (ticket.uri != uri || !cache.ownership.isCurrent(ticket)) return false
        if (workspace.fingerprintFor(uri) != fingerprint) return false
        val admission = snapshot.admissionFingerprint ?: return false
        val cached = cache.getCachedRecord(uri, fingerprint) ?: return false
        if (cached.content != snapshot.sourceText || cached.parseResult !== snapshot.parseResult) return false
        if (cached.admissionFingerprint != admission) return false
        if (!sameBuffer(snapshot.buffer, documents?.getBuffer(uri))) return false
        return cache.ownership.isCurrent(ticket)
    }

    private fun sameBuffer(captured: OpenBuffer?, current: OpenBuffer?): Boolean = when {
        captured == null -> current == null
        current == null -> false
        else -> current.incarnation === captured.incarnation && current.snapshot === captured.snapshot
    }
}

// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.library

import java.net.URI

internal data class LibraryDocumentIdentity(val workspaceRoot: URI, val documentUri: URI) {
    init {
        require(workspaceRoot.isAbsolute && documentUri.isAbsolute) { "Document and workspace URIs must be absolute" }
    }
}

internal data class LibraryProfileIdentity(
    val profileId: String,
    val configurationGeneration: Long,
    val authorizationScopeId: String,
    val authorizationGeneration: Long,
) {
    init {
        require(profileId.isNotBlank() && authorizationScopeId.isNotBlank()) {
            "Profile and scope IDs must not be blank"
        }
        require(configurationGeneration >= 0 && authorizationGeneration >= 0) { "Generations must be nonnegative" }
    }
}

/** Engine and target identifiers must include the caller's relevant versions, options and compiler policy. */
internal data class LibraryAnalysisIdentity(val engine: String, val target: String, val indexSchema: String) {
    init {
        require(engine.isNotBlank() && target.isNotBlank() && indexSchema.isNotBlank()) {
            "Analysis identity fields must not be blank"
        }
    }
}

internal data class LibraryContextRequest(
    val document: LibraryDocumentIdentity,
    val documentGeneration: Long,
    val profile: LibraryProfileIdentity,
    val analysis: LibraryAnalysisIdentity,
) {
    init {
        require(documentGeneration >= 0) { "Document generation must be nonnegative" }
    }
}

internal data class LibraryContextIdentity(val request: LibraryContextRequest, val inputsFingerprint: String)

internal class LibraryContextSnapshot(val request: LibraryContextRequest, val inputs: LibraryInputSnapshot) {
    val identity = LibraryContextIdentity(request, inputs.fingerprint)
}

/**
 * A disconnected publication primitive. Integration must also fence actual reads, outputs and authorization changes.
 */
internal class LibraryContextPublication(private val document: LibraryDocumentIdentity) {
    internal class Attempt internal constructor(val request: LibraryContextRequest)

    private var active: Attempt? = null
    private var published: LibraryContextSnapshot? = null

    @Synchronized
    fun begin(request: LibraryContextRequest): Attempt {
        require(request.document == document) { "Library context belongs to a different document" }
        val attempt = Attempt(request)
        active = attempt
        published = null
        return attempt
    }

    @Synchronized
    fun publish(attempt: Attempt, inputs: LibraryInputSnapshot): Boolean {
        if (active !== attempt || published != null) return false
        published = LibraryContextSnapshot(attempt.request, inputs)
        return true
    }

    @Synchronized
    fun cancel(attempt: Attempt) {
        if (active !== attempt) return
        active = null
        published = null
    }

    @Synchronized
    fun publishedFor(request: LibraryContextRequest): LibraryContextSnapshot? = published?.takeIf {
        it.request ==
            request
    }

    /** Checks recorded identity only. The producer must invalidate changed bytes and authorization separately. */
    @Synchronized
    fun current(identity: LibraryContextIdentity): LibraryContextSnapshot? =
        published?.takeIf { it.identity == identity }
}

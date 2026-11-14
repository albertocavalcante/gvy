// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.OpenBuffer
import com.github.albertocavalcante.nativeapi.ParseResult

/** Source and parse components captured from one cache entry. Consumers treat the AST as read-only. */
internal data class CompiledDocumentSnapshot(
    val sourceText: String,
    val parseResult: ParseResult,
    val buffer: OpenBuffer? = null,
    val configurationFingerprint: String? = null,
    val ownershipTicket: ArtifactOwnership.Ticket? = null,
    val admissionFingerprint: String? = null,
)

// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import java.net.URI
import java.nio.file.Path

/** Detached source-view provenance. No live compiler or request resources are retained. */
data class SourceDocumentDescriptor(
    val sourceDigest: String,
    val contentDigest: String,
    val entryName: String,
    val contentPath: Path,
    val origin: SourceOrigin,
    val context: SourceContextIdentity,
    val displayName: String,
    val languageId: String,
) {
    init {
        require(SHA256.matches(sourceDigest) && SHA256.matches(contentDigest)) {
            "Expected source SHA-256 identities"
        }
        require(contentPath.isAbsolute) { "Source content path must be absolute" }
        require(entryName.isNotBlank() && entryName.length <= MAX_ENTRY_LENGTH)
        require(!entryName.startsWith('/') && !entryName.contains('\\'))
        require(entryName.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Invalid source entry"
        }
        require(languageId == "java" || languageId == "groovy") { "Unsupported source language" }
        require(displayName.isNotBlank() && displayName.length <= MAX_ENTRY_LENGTH)
    }

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
        const val MAX_ENTRY_LENGTH = 4096
    }
}

fun interface SourcePresentation {
    /** Registers provenance before publishing a navigable URI. */
    fun register(descriptor: SourceDocumentDescriptor): URI
}

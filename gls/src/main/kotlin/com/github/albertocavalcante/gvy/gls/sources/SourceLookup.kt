// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.build.defaultVerifiedSourceCacheRoot
import java.net.URI
import java.nio.file.Path

sealed interface SourceLookupResult {
    data class Located(val uri: URI, val binaryName: String, val definition: SourceDefinition) : SourceLookupResult
    data class FileOnly(val uri: URI, val binaryName: String, val reason: MissingSourceReason) : SourceLookupResult
    data class Unavailable(val reason: MissingSourceReason) : SourceLookupResult
    data class Failed(val failure: SourceFailure) : SourceLookupResult
    data object Ambiguous : SourceLookupResult
    data object Stale : SourceLookupResult
}

enum class MissingSourceReason {
    UNKNOWN_ORIGIN,
    NO_ATTACHMENT,
    SOURCE_NOT_PUBLISHED,
    BINARY_TYPE_NOT_PRESENT,
    TYPE_NOT_PRESENT,
    DECLARATION_NOT_LOCATED,
    UNSUPPORTED_LAYOUT,
    DOWNLOADS_DISABLED,
    INTEGRITY_UNAVAILABLE,
    BINARY_ORIGIN_MISMATCH,
}

/** Pure namespace inventory for editor guards, including cache files whose sidecar metadata is absent. */
fun defaultManagedSourceRoots(extractionRoot: Path = defaultSourceCacheRoot()): List<Path> = listOf(
    extractionRoot,
    extractionRoot.resolve("content-v2"),
    extractionRoot.resolve("attachments-v2"),
    extractionRoot.resolve("views-v2"),
    defaultSourceViews(),
    defaultSourceAttachmentRoot(),
    defaultVerifiedSourceCacheRoot(),
)

/** Dedicated source namespace, retained so existing extracted views stay protected. */
fun defaultSourceCacheRoot(): Path = Path.of(System.getProperty("user.home"), ".gls", "cache", "extracted-sources")

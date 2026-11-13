// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.build.ArtifactStamp
import com.github.albertocavalcante.gvy.build.ResolvedArtifactCoordinates
import com.github.albertocavalcante.gvy.build.defaultVerifiedSourceCacheRoot
import com.github.albertocavalcante.gvy.gls.documentation.Documentation
import java.net.URI
import java.nio.file.Path

/** The resource selected by semantic resolution, rather than a class name found in an unrelated index. */
sealed interface SourceOrigin {
    data class Archive(
        val binaryJar: Path,
        val coordinates: ResolvedArtifactCoordinates? = null,
        val observedStamp: ArtifactStamp? = null,
        /** SHA-256 of the selected binary bytes; file timestamps alone do not identify an artifact. */
        val binarySha256: String? = null,
    ) : SourceOrigin {
        init {
            require(binarySha256 == null || isSha256(binarySha256)) { "Expected a binary SHA-256 identity" }
        }
    }

    data class Bundled(val componentId: String, val binaryContainer: URI) : SourceOrigin

    data class Jdk(val imageHome: Path, val module: String) : SourceOrigin

    data class Unknown(val resource: URI) : SourceOrigin
}

/** Metadata retained by a source-view descriptor. It owns no parser, classloader or coroutine resources. */
data class SourceContextIdentity(
    val id: String,
    val documentUri: URI? = null,
    val compileFingerprint: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Source context identity must be nonempty" }
    }
}

enum class SourceLookupPolicy { LOCAL_ONLY, ALLOW_VERIFIED_DOWNLOADS }

/** Request-scoped validation; presentation registries retain [identity] only. */
class SourceLookupContext(
    val identity: SourceContextIdentity,
    val policy: SourceLookupPolicy = SourceLookupPolicy.LOCAL_ONLY,
    validateCurrent: (suspend () -> Boolean)? = null,
    private val isCurrent: () -> Boolean,
) {
    private val validator = validateCurrent

    fun isCurrent(): Boolean = isCurrent.invoke()

    /** Fresh validation for the final publication boundary of a source lookup. */
    suspend fun validateCurrent(): Boolean = validator?.invoke() ?: isCurrent()
}

data class ResolvedExternalSymbol(val binaryName: String, val origin: SourceOrigin, val memberName: String? = null)

data class ResolvedSourceRequest(val symbol: ResolvedExternalSymbol, val context: SourceLookupContext)

/** Immutable zero-based UTF-16 coordinates, independent of LSP's mutable transport objects. */
data class SourceSpan(val startLine: Int, val startCharacter: Int, val endLine: Int, val endCharacter: Int) {
    init {
        require(startLine >= 0 && startCharacter >= 0 && endLine >= 0 && endCharacter >= 0)
        require(
            endLine > startLine ||
                (endLine == startLine && endCharacter >= startCharacter),
        )
    }

    fun contains(other: SourceSpan): Boolean = (
        other.startLine > startLine ||
            (other.startLine == startLine && other.startCharacter >= startCharacter)
        ) &&
        (
            other.endLine < endLine ||
                (other.endLine == endLine && other.endCharacter <= endCharacter)
            )
}

data class SourceDefinition(
    val declarationRange: SourceSpan,
    val nameRange: SourceSpan,
    val documentation: Documentation = Documentation.EMPTY,
) {
    init {
        require(declarationRange.contains(nameRange)) { "Declaration must contain its selected name" }
    }
}

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

enum class SourceFailure { INTEGRITY_MISMATCH, UNSAFE_ARCHIVE, LIMIT_EXCEEDED, IO_FAILURE }

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

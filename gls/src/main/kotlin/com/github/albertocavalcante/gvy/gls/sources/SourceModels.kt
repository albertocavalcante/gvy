// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.build.ArtifactStamp
import com.github.albertocavalcante.gvy.build.ResolvedArtifactCoordinates
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

enum class SourceFailure { INTEGRITY_MISMATCH, UNSAFE_ARCHIVE, LIMIT_EXCEEDED, IO_FAILURE }

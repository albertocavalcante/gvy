// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import java.nio.file.Path

private const val MAX_COORDINATE_PART_LENGTH = 256

/** The binary digest binds a source lookup to the actual artifact, including privately patched same-GAV releases. */
data class VerifiedSourceArtifactRequest(
    val coordinates: ResolvedArtifactCoordinates,
    val selectedBinarySha256: String,
    val allowDownload: Boolean,
    val contextId: String,
)

/** Repository checksums establish both selected binary identity and expected source identity before archive parsing. */
interface VerifiedSourceArtifactResolver {
    suspend fun resolveVerifiedSourceArtifact(request: VerifiedSourceArtifactRequest): VerifiedSourceArtifactResult
}

sealed interface VerifiedSourceArtifactResult {
    data class Available(val path: Path, val expectedSha256: String) : VerifiedSourceArtifactResult
    data object NotAvailable : VerifiedSourceArtifactResult
    data object IntegrityUnavailable : VerifiedSourceArtifactResult
    data object DownloadsDisabled : VerifiedSourceArtifactResult
    data object UnsupportedArtifact : VerifiedSourceArtifactResult
    data object BinaryMismatch : VerifiedSourceArtifactResult
    data object LimitExceeded : VerifiedSourceArtifactResult
    data object Failed : VerifiedSourceArtifactResult
}

/** Pure managed-namespace inventory; asking for the path does not create it. */
fun defaultVerifiedSourceCacheRoot(cacheDir: Path = defaultSourceArtifactCacheRoot()): Path =
    cacheDir.resolve("verified-v1")

internal fun defaultSourceArtifactCacheRoot(): Path =
    Path.of(System.getProperty("user.home"), ".groovy-lsp", "cache", "sources")

internal fun supportsVerifiedSourceCoordinates(coordinates: ResolvedArtifactCoordinates): Boolean {
    val parts = listOf(coordinates.group, coordinates.module, coordinates.version)
    return parts.all { part ->
        part.length in 1..MAX_COORDINATE_PART_LENGTH &&
            part != "." &&
            part != ".." &&
            part.all { character ->
                character.isLetterOrDigit() ||
                    character == '.' ||
                    character == '_' ||
                    character == '-' ||
                    character == '+'
            }
    } &&
        coordinates.group.split('.').none { it.isEmpty() } &&
        coordinates.classifier.isNullOrEmpty() &&
        (coordinates.extension == null || coordinates.extension == "jar")
}

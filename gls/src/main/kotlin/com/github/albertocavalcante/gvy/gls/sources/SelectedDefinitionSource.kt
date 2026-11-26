// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Navigate from the compiler-selected class. The normal service materializes only its paired source. */
internal suspend fun SourceNavigator.definitionFor(
    type: Class<*>,
    method: String?,
    observe: (suspend () -> Boolean) -> Unit,
): SourceNavigator.SourceResult? {
    val resource = type.binaryResourceUri() ?: return null
    observe { type.binaryResourceUri() == resource }
    if (this is SourceNavigationService) return selectedDefinition(type, resource, method, observe)
    return navigateWithAttachment(type, resource, method, observe)
}

private suspend fun SourceNavigator.navigateWithAttachment(
    type: Class<*>,
    resource: URI,
    method: String?,
    observe: (suspend () -> Boolean) -> Unit,
): SourceNavigator.SourceResult? {
    val binary = parseJarClassResource(resource)
    if (binary != null) {
        val original = withContext(Dispatchers.IO) { classDigest(binary) } ?: return null
        observe { withContext(Dispatchers.IO) { classDigest(binary) == original } }
    }
    val result = if (method == null) {
        navigateToSource(resource, type.name)
    } else {
        navigateToMethodSource(resource, type.name, method)
    }
    // Alternate navigators own the association with the supplied binary resource.
    val source = (result as? SourceNavigator.SourceResult.SourceLocation)?.uri
    val path = source?.takeIf { it.scheme.equals("file", ignoreCase = true) }?.let(Path::of)
    if (path != null) {
        val limit = SourceStoreLimits().maxEntryBytes
        val digest = withContext(Dispatchers.IO) { fileDigest(path, limit) } ?: return null
        observe { withContext(Dispatchers.IO) { fileDigest(path, limit) == digest } }
    }
    return result
}

internal class SelectedSourceArchive(val input: SourceArchiveInput?, val prefix: String, val current: () -> Boolean)

internal fun selectedSourceArchive(
    type: Class<*>,
    resource: URI,
    bundled: BundledSourceCatalog,
    adjacent: (Path) -> Path?,
): SelectedSourceArchive? {
    if (resource.scheme == "jrt") {
        // This resource has already been matched to the boot layer and module of the selected Class.
        val source = Path.of(System.getProperty("java.home"), "lib", "src.zip")
        val input = source.takeIf { Files.isRegularFile(it) }?.let(SourceArchiveInput::Local)
        return SelectedSourceArchive(input, type.module.name + "/") {
            type.binaryResourceUri() == resource && Files.isRegularFile(source) == (input != null)
        }
    }
    val binary = parseJarClassResource(resource) ?: return null
    val original = classDigest(binary) ?: return null
    val packaged = bundled.selectedOrigin(resource, type.name)
    val pinned = packaged?.let(bundled::sourceInput)
        ?: bundled.sourceInputForArchive(SourceOrigin.Archive(binary.archive), type.name)
    val sibling = if (pinned == null) adjacent(binary.archive) else null
    val input = pinned ?: sibling?.let(SourceArchiveInput::Local)
    return SelectedSourceArchive(input, "") {
        type.binaryResourceUri() == resource && classDigest(binary) == original &&
            (pinned != null || adjacent(binary.archive) == sibling)
    }
}

private fun classDigest(resource: JarClassResource): String? = try {
    ZipFile(resource.archive.toFile()).use { zip ->
        val entry = zip.getEntry(resource.entryName) ?: return@use null
        zip.getInputStream(entry).use { copyAndDigest(it, SourceStoreLimits().maxEntryBytes) }
    }
} catch (_: IOException) {
    null
}

internal fun fileDigest(path: Path, limit: Long): String? = try {
    Files.newInputStream(path).use { copyAndDigest(it, limit) }
} catch (_: IOException) {
    null
}

// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import java.net.URI
import java.nio.file.Path

data class JarClassResource(val archive: Path, val entryName: String)

/** URI decoding happens once at the URI boundary; a percent-encoded path is never passed to Path as raw text. */
fun parseJarClassResource(resource: URI): JarClassResource? {
    if (resource.scheme != "jar") return null
    return parseJarResourcePart(resource.rawSchemeSpecificPart)
}

private fun parseJarResourcePart(part: String): JarClassResource? {
    val separator = part.indexOf("!/")
    if (separator < 0) return null
    return try {
        val container = URI(part.substring(0, separator))
        val entry = URI("gvy:/" + part.substring(separator + 2)).path.removePrefix("/")
        if (container.scheme == "file" && isSafeJarClassEntry(entry)) {
            JarClassResource(Path.of(container).toAbsolutePath().normalize(), entry)
        } else {
            null
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.net.URISyntaxException) {
        null
    }
}

private fun isSafeJarClassEntry(entry: String): Boolean =
    !entry.contains("!/") && entry.split('/').none(::isUnsafeJarClassSegment)

private fun isUnsafeJarClassSegment(segment: String): Boolean = segment.isEmpty() || isDotSegment(segment)

private fun isDotSegment(segment: String): Boolean = segment == ".." || segment == "."

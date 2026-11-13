// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import java.net.URI
import java.nio.file.Path

data class JarClassResource(val archive: Path, val entryName: String)

/** URI decoding happens once at the URI boundary; a percent-encoded path is never passed to Path as raw text. */
fun parseJarClassResource(resource: URI): JarClassResource? {
    if (resource.scheme != "jar") return null
    val part = resource.rawSchemeSpecificPart
    val separator = part.indexOf("!/")
    if (separator < 0) return null
    return try {
        val container = URI(part.substring(0, separator))
        if (container.scheme != "file") return null
        val entry = URI("gvy:/" + part.substring(separator + 2)).path.removePrefix("/")
        if (
            entry.contains("!/") ||
            entry.split('/').any { segment -> segment == ".." || segment == "." || segment.isEmpty() }
        ) {
            return null
        }
        JarClassResource(Path.of(container).toAbsolutePath().normalize(), entry)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.net.URISyntaxException) {
        null
    }
}

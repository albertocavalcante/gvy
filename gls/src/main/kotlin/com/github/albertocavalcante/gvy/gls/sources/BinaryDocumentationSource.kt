// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.gls.documentation.Documentation
import java.net.JarURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Path

/** Source attachments paired with the selected class even when another artifact defines the same name. */
interface BinaryDocumentationSource {
    suspend fun documentationFor(type: Class<*>): Documentation?
}

internal suspend fun SourceNavigator.documentationForSelectedBinary(type: Class<*>): Documentation? {
    if (this is BinaryDocumentationSource) return documentationFor(type)
    val origin = type.binaryResourceUri() ?: return null
    val source = navigateToSource(origin, type.name)
    return (source as? SourceNavigator.SourceResult.SourceLocation)?.documentation
}

internal fun Class<*>.binaryResourceUri(): URI? {
    val entry = name.replace('.', '/') + ".class"
    val resource = getResource("/$entry") ?: return null
    return if (resource.protocol == "jrt") {
        resource.toURI().takeIf {
            module.layer === ModuleLayer.boot() && it.path == "/${module.name}/$entry"
        }
    } else {
        artifactResourceUri(resource, entry)
    }
}

private fun Class<*>.artifactResourceUri(resource: URL, entry: String): URI? {
    // Class.getResource delegates to its loader; a parent can supply another artifact's same-named entry.
    val location = protectionDomain?.codeSource?.location?.takeIf { it.protocol == "file" } ?: return null
    val artifact = Path.of(location.toURI())
    val matchesArtifact = when (resource.protocol) {
        "jar" -> {
            val archive = (resource.openConnection() as? JarURLConnection)?.jarFileURL
            archive?.protocol == "file" && Path.of(archive.toURI()) == artifact
        }
        "file" -> Path.of(resource.toURI()) == artifact.resolve(entry)
        else -> false
    }
    return resource.toURI().takeIf { matchesArtifact }
}

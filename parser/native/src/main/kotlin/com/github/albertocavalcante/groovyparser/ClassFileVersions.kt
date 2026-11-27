// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Gives each class file a URL that changes when the file, or the jar holding it, changes on disk.
 *
 * The compiler reads a dependency class through its URL and remembers what it read under that URL for as long as
 * the process lives. A class rebuilt at the same path therefore kept its old members in every later compile. A URL
 * that carries the container's size and modification time makes the rebuilt file a different entry, while an
 * unchanged file keeps its URL and stays remembered.
 *
 * One instance serves one compile class loader, so every lookup in a compile sees the same version of a container.
 */
internal class ClassFileVersions {
    private val versions = ConcurrentHashMap<Path, String>()

    /** [resource] under a version-carrying URL, or [resource] itself when it is not a readable local file or jar. */
    fun versioned(resource: URL): URL {
        val container = container(resource) ?: return resource
        val version = versions.computeIfAbsent(container, ::versionOf)
        if (version.isEmpty()) return resource
        // URL.of(URI, URLStreamHandler) needs Java 20; this constructor is the Java 17 way to attach a handler.
        @Suppress("DEPRECATION")
        return URL(null, "$SCHEME:$version:${resource.toExternalForm()}", Reading(resource))
    }

    private fun container(resource: URL): Path? = try {
        when (resource.protocol) {
            "file" -> Path.of(resource.toURI())
            "jar" -> Path.of(URI.create(resource.path.substringBefore(JAR_ENTRY_SEPARATOR)))
            else -> null
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.net.URISyntaxException) {
        null
    } catch (_: java.nio.file.FileSystemNotFoundException) {
        null
    }

    private fun versionOf(container: Path): String = try {
        "${Files.size(container)}-${Files.getLastModifiedTime(container).toMillis()}"
    } catch (_: java.io.IOException) {
        ""
    }

    /** Opens the original resource; the version-carrying URL only names it. */
    private class Reading(private val resource: URL) : URLStreamHandler() {
        override fun openConnection(url: URL): URLConnection = resource.openConnection()
    }

    private companion object {
        const val SCHEME = "gvy-class-version"
        const val JAR_ENTRY_SEPARATOR = "!/"
    }
}

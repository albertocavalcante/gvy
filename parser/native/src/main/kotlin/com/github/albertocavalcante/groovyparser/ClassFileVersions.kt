// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.NativeArtifactStamp
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.file.FileSystemNotFoundException
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
 * A resource whose container cannot be named or observed keeps its own URL, which is the behaviour before this
 * class existed: it is read once and then remembered.
 *
 * One instance serves one compile class loader, so every lookup in a compile sees the same version of a container.
 */
internal class ClassFileVersions {
    private val versions = ConcurrentHashMap<Path, String>()

    /** [resource] under a version-carrying URL, or [resource] itself when it is not an observable file or jar. */
    fun versioned(resource: URL): URL {
        val container = container(resource) ?: return resource
        // Observed outside the map: a slow file system must not hold up lookups of other containers.
        val version = versions[container] ?: versionOf(container).also { versions.putIfAbsent(container, it) }
        if (version.isEmpty()) return resource
        @Suppress("DEPRECATION") // URL.of(URI, URLStreamHandler) needs Java 20; this is the Java 17 way.
        return URL(null, "$SCHEME:$version:${resource.toExternalForm()}", Reading(resource))
    }

    private fun container(resource: URL): Path? {
        val location = when (resource.protocol) {
            "file" -> resource.toExternalForm()
            "jar" -> resource.path.substringBefore(JAR_ENTRY_SEPARATOR)
            else -> return null
        }
        return try {
            Path.of(URI.create(location))
        } catch (e: IllegalArgumentException) {
            // Not a path on this platform, such as a `file:` URL that names another host.
            logger.debug(e) { "Class file $resource keeps its own URL: its container is not a local path" }
            null
        } catch (e: FileSystemNotFoundException) {
            // A jar that is not a local file, such as `jar:http:`.
            logger.debug(e) { "Class file $resource keeps its own URL: its container is not on a file system" }
            null
        }
    }

    /** The container's size and modification time, or empty when it cannot be observed. */
    private fun versionOf(container: Path): String {
        val stamp = NativeArtifactStamp.observe(container, maxPaths = 1)
        if (stamp == null) logger.debug { "Class files in $container keep their own URLs: it cannot be observed" }
        return stamp?.let { "${it.size}-${it.lastModifiedMillis}" }.orEmpty()
    }

    /** Opens the original resource; the version-carrying URL only names it. */
    private class Reading(private val resource: URL) : URLStreamHandler() {
        override fun openConnection(url: URL): URLConnection = resource.openConnection()
    }

    private companion object {
        val logger = KotlinLogging.logger {}
        const val SCHEME = "gvy-class-version"
        const val JAR_ENTRY_SEPARATOR = "!/"
    }
}

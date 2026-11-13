// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.NativeArtifactStamp
import com.github.albertocavalcante.nativeapi.NativeCompilerSdkIdentity
import groovy.lang.GroovyObject
import groovy.lang.GroovySystem
import java.io.IOException
import java.io.InputStream
import java.net.JarURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.Properties
import java.util.jar.JarFile

/** Exact trusted core entries, never the rest of the language server's application classpath. */
internal class RuntimeGroovySdk private constructor(
    val identity: NativeCompilerSdkIdentity,
    private val loader: ClassLoader,
    private val classes: Map<String, String>,
) {
    fun resource(entry: String): URL? {
        val expected = classes[entry] ?: return null
        val resource = loader.getResource(entry) ?: throw IOException("Trusted compiler SDK resource disappeared")
        if (container(resource) != identity.container || digest(open(resource), MAX_CLASS_BYTES) != expected) {
            throw IOException("Trusted compiler SDK resource identity changed")
        }
        return resource
    }

    fun load(binaryName: String): Class<*>? {
        resource(binaryName.replace('.', '/') + ".class") ?: return null
        val selected = loader.loadClass(binaryName)
        if (selected.protectionDomain?.codeSource?.location?.toURI()?.let(::fileContainer) != identity.container) {
            throw ClassNotFoundException("Trusted compiler SDK class origin is unavailable: " + binaryName)
        }
        return selected
    }

    companion object {
        private const val PREFIX = "META-INF/gvy/sources/"
        private const val MAX_METADATA_BYTES = 2L * 1024 * 1024
        private const val MAX_CLASS_BYTES = 2L * 1024 * 1024
        private const val MAX_COMPONENT_BYTES = 64L * 1024 * 1024
        private const val MAX_CONTAINER_BYTES = 512L * 1024 * 1024
        private const val MAX_CLASSES = 20_000
        private val SHA256 = Regex("[a-f0-9]{64}")
        private val trusted by lazy { discover() }

        /** Initialization is once per trusted runtime and retains only its bounded entry table and runtime loader. */
        fun current(): RuntimeGroovySdk? = trusted?.takeIf {
            NativeArtifactStamp.observe(Path.of(it.identity.container), maxPaths = 1) == it.identity.containerStamp
        }

        private fun discover(): RuntimeGroovySdk? {
            return try {
                val sdkClass = GroovyObject::class.java
                val loader = sdkClass.classLoader
                val location = sdkClass.protectionDomain?.codeSource?.location?.toURI()
                if (location?.scheme != "file") return null

                val path = Path.of(location).toRealPath()
                val containerStamp = NativeArtifactStamp.observe(path, maxPaths = 1)
                if (
                    containerStamp == null ||
                    containerStamp.files != null ||
                    containerStamp.size > MAX_CONTAINER_BYTES
                ) {
                    return null
                }

                val container = path.toUri()
                val indexed = packaged(loader, container) ?: unshaded(path) ?: return null
                if (NativeArtifactStamp.observe(path, maxPaths = 1) != containerStamp) return null

                val identity = NativeCompilerSdkIdentity(
                    componentId = indexed.first,
                    container = container,
                    containerStamp = containerStamp,
                    indexDigest = indexDigest(indexed.second),
                )
                RuntimeGroovySdk(identity, loader, Collections.unmodifiableMap(indexed.second))
                    .takeIf { sdk -> sdk.resource("groovy/lang/GroovyObject.class") != null }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun packaged(loader: ClassLoader, expectedContainer: URI): Pair<String, Map<String, String>>? {
            val manifest = loader.getResource(PREFIX + "groovy.properties")
                ?.takeIf { resource -> container(resource) == expectedContainer }
                ?: return null
            val index = loader.getResource(PREFIX + "groovy-class-digests.properties")
                ?.takeIf { resource -> container(resource) == expectedContainer }
                ?: return null
            val metadata = properties(open(manifest))
            if (
                metadata.getProperty("format") != "1" ||
                metadata.getProperty("module") != "groovy" ||
                metadata.getProperty("version") != GroovySystem.getVersion() ||
                metadata.getProperty("group") !in setOf("org.apache.groovy", "org.codehaus.groovy")
            ) {
                return null
            }
            val classes = properties(open(index))
            if (classes.size > MAX_CLASSES) return null
            val entries = classes.stringPropertyNames().associateWith { classes.getProperty(it) }
            if (entries.keys.any { !classEntry(it) } || entries.values.any { !SHA256.matches(it) }) return null
            val id = metadata.getProperty("group") + ":groovy:" + metadata.getProperty("version")
            return (id to entries).takeIf { "groovy/lang/GroovyObject.class" in entries }
        }

        /** Development/native-only execution can identify the actual standalone core component by its own manifest. */
        private fun unshaded(path: Path): Pair<String, Map<String, String>>? {
            if (java.nio.file.Files.size(path) > MAX_COMPONENT_BYTES) return null
            return JarFile(path.toFile()).use { jar ->
                val metadata = jar.manifest?.mainAttributes ?: return null
                if (
                    metadata.getValue("Automatic-Module-Name") != "org.apache.groovy" ||
                    metadata.getValue("Implementation-Version") != GroovySystem.getVersion()
                ) {
                    return null
                }
                val entries = sortedMapOf<String, String>()
                var expanded = 0L
                val iterator = jar.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    if (!entry.isDirectory && entry.name.endsWith(".class")) {
                        if (
                            entries.size >= MAX_CLASSES ||
                            !classEntry(entry.name) ||
                            entry.size < 0 ||
                            entry.size > MAX_CLASS_BYTES ||
                            entry.size > MAX_COMPONENT_BYTES - expanded
                        ) {
                            return null
                        }
                        expanded += entry.size
                        val digest = digest(jar.getInputStream(entry), MAX_CLASS_BYTES)
                        if (entries.put(entry.name, digest) != null) return null
                    }
                }
                ("groovy-core:" + GroovySystem.getVersion() to entries)
                    .takeIf { "groovy/lang/GroovyObject.class" in entries }
            }
        }

        private fun classEntry(entry: String): Boolean = entry.endsWith(".class") &&
            entry.length <= 1024 &&
            !entry.startsWith("/") &&
            '\\' !in entry &&
            ':' !in entry &&
            entry.removeSuffix(".class").split('/').all { part ->
                part.isNotEmpty() &&
                    Character.isJavaIdentifierStart(part.codePointAt(0)) &&
                    part.codePoints().allMatch(Character::isJavaIdentifierPart)
            }

        private fun properties(input: InputStream): Properties = input.use { stream ->
            val bytes = stream.readNBytes(MAX_METADATA_BYTES.toInt() + 1)
            if (bytes.size > MAX_METADATA_BYTES) throw IOException("Compiler SDK metadata exceeds limit")
            Properties().apply { bytes.inputStream().reader(StandardCharsets.UTF_8).use(::load) }
        }

        private fun indexDigest(entries: Map<String, String>): String = MessageDigest.getInstance("SHA-256").run {
            entries.toSortedMap().forEach { (name, sha) ->
                update((name + "=" + sha + "\n").toByteArray(StandardCharsets.UTF_8))
            }
            digest().joinToString("") { "%02x".format(it) }
        }

        private fun digest(input: InputStream, maximum: Long): String = input.use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > maximum) throw IOException("Compiler SDK resource exceeds limit")
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun container(resource: URL): URI? {
            if (resource.protocol != "jar") return null
            val connection = resource.openConnection() as? JarURLConnection ?: return null
            val jar = connection.jarFileURL.toURI()
            return fileContainer(jar)
        }

        private fun fileContainer(location: URI): URI? =
            if (location.scheme == "file") Path.of(location).toRealPath().toUri() else null

        private fun open(resource: URL): InputStream = resource.openConnection()
            .apply { useCaches = false }
            .getInputStream()
    }
}

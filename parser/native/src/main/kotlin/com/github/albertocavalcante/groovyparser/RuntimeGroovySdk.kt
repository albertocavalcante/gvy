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
import java.util.jar.JarEntry
import java.util.jar.JarFile

/** Bounded archive and digest operations shared by SDK discovery and resource verification. */
private object RuntimeGroovySdkArchive {
    const val SOURCE_INDEX_PREFIX = "META-INF/gvy/sources/"
    const val MAX_METADATA_BYTES = 2L * 1024 * 1024
    const val MAX_CLASS_BYTES = 2L * 1024 * 1024
    const val MAX_COMPONENT_BYTES = 64L * 1024 * 1024
    const val MAX_CONTAINER_BYTES = 512L * 1024 * 1024
    const val MAX_CLASSES = 20_000
    private const val MAX_CLASS_ENTRY_NAME_LENGTH = 1024
    private const val DIGEST_BUFFER_BYTES = 8192
    private val SHA256 = Regex("[a-f0-9]{64}")

    fun indexedClasses(classes: Properties): Map<String, String>? {
        if (classes.size > MAX_CLASSES) return null
        val entries = classes.stringPropertyNames().associateWith { classes.getProperty(it) }
        val validEntries = entries.keys.all(::classEntry) && entries.values.all(SHA256::matches)
        return entries.takeIf { validEntries && "groovy/lang/GroovyObject.class" in it }
    }

    fun unshaded(path: Path): Pair<String, Map<String, String>>? =
        if (java.nio.file.Files.size(path) > MAX_COMPONENT_BYTES) {
            null
        } else {
            JarFile(path.toFile()).use(::indexUnshadedJar)
        }

    private fun indexUnshadedJar(jar: JarFile): Pair<String, Map<String, String>>? {
        val metadata = jar.manifest?.mainAttributes
        val validMetadata = metadata?.let {
            it.getValue("Automatic-Module-Name") == "org.apache.groovy" &&
                it.getValue("Implementation-Version") == GroovySystem.getVersion()
        } == true
        val entries = if (validMetadata) classDigests(jar) else null
        return entries?.let { "groovy-core:${GroovySystem.getVersion()}" to it }
    }

    private fun classDigests(jar: JarFile): Map<String, String>? {
        val entries = sortedMapOf<String, String>()
        var expanded = 0L
        var valid = true
        val iterator = jar.entries()
        while (iterator.hasMoreElements() && valid) {
            val entry = iterator.nextElement()
            if (entry.isDirectory || !entry.name.endsWith(".class")) continue
            val expandedBytes = indexClassEntry(jar, entry, entries, expanded)
            if (expandedBytes == null) {
                valid = false
            } else {
                expanded = expandedBytes
            }
        }
        return entries.takeIf { valid && "groovy/lang/GroovyObject.class" in it }
    }

    private fun indexClassEntry(
        jar: JarFile,
        entry: JarEntry,
        entries: MutableMap<String, String>,
        expanded: Long,
    ): Long? {
        val withinBounds = entries.size < MAX_CLASSES &&
            classEntry(entry.name) &&
            entry.size >= 0 &&
            entry.size <= MAX_CLASS_BYTES &&
            entry.size <= MAX_COMPONENT_BYTES - expanded
        if (!withinBounds) return null
        val entryDigest = digest(jar.getInputStream(entry), MAX_CLASS_BYTES)
        return (expanded + entry.size).takeIf { entries.put(entry.name, entryDigest) == null }
    }

    fun properties(input: InputStream): Properties = input.use { stream ->
        val bytes = stream.readNBytes(MAX_METADATA_BYTES.toInt() + 1)
        if (bytes.size > MAX_METADATA_BYTES) throw IOException("Compiler SDK metadata exceeds limit")
        Properties().apply { bytes.inputStream().reader(StandardCharsets.UTF_8).use(::load) }
    }

    fun indexDigest(entries: Map<String, String>): String = MessageDigest.getInstance("SHA-256").run {
        entries.toSortedMap().forEach { (name, sha) ->
            update((name + "=" + sha + "\n").toByteArray(StandardCharsets.UTF_8))
        }
        digest().joinToString("") { "%02x".format(it) }
    }

    fun digest(input: InputStream, maximum: Long): String = input.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DIGEST_BUFFER_BYTES)
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

    fun container(resource: URL): URI? {
        if (resource.protocol != "jar") return null
        val connection = resource.openConnection() as? JarURLConnection ?: return null
        val jar = connection.jarFileURL.toURI()
        return fileContainer(jar)
    }

    fun fileContainer(location: URI): URI? =
        if (location.scheme == "file") Path.of(location).toRealPath().toUri() else null

    fun open(resource: URL): InputStream = resource.openConnection()
        .apply { useCaches = false }
        .getInputStream()

    private fun classEntry(entry: String): Boolean = entry.endsWith(".class") &&
        entry.length <= MAX_CLASS_ENTRY_NAME_LENGTH &&
        !entry.startsWith("/") &&
        '\\' !in entry &&
        ':' !in entry &&
        entry.removeSuffix(".class").split('/').all { part ->
            part.isNotEmpty() &&
                Character.isJavaIdentifierStart(part.codePointAt(0)) &&
                part.codePoints().allMatch(Character::isJavaIdentifierPart)
        }
}

/** Exact trusted core entries, never the rest of the language server's application classpath. */
internal class RuntimeGroovySdk private constructor(
    val identity: NativeCompilerSdkIdentity,
    private val loader: ClassLoader,
    private val classes: Map<String, String>,
) {
    fun resource(entry: String): URL? {
        val expected = classes[entry] ?: return null
        val resource = loader.getResource(entry) ?: throw IOException("Trusted compiler SDK resource disappeared")
        if (
            RuntimeGroovySdkArchive.container(resource) != identity.container ||
            RuntimeGroovySdkArchive.digest(
                RuntimeGroovySdkArchive.open(resource),
                RuntimeGroovySdkArchive.MAX_CLASS_BYTES,
            ) != expected
        ) {
            throw IOException("Trusted compiler SDK resource identity changed")
        }
        return resource
    }

    fun load(binaryName: String): Class<*>? {
        resource(binaryName.replace('.', '/') + ".class") ?: return null
        val selected = loader.loadClass(binaryName)
        if (
            selected.protectionDomain?.codeSource?.location?.toURI()
                ?.let { RuntimeGroovySdkArchive.fileContainer(it) } != identity.container
        ) {
            throw ClassNotFoundException("Trusted compiler SDK class origin is unavailable: " + binaryName)
        }
        return selected
    }

    companion object {
        private val trusted by lazy { discover() }

        /** Initialization is once per trusted runtime and retains only its bounded entry table and runtime loader. */
        fun current(): RuntimeGroovySdk? = trusted?.takeIf {
            NativeArtifactStamp.observe(Path.of(it.identity.container), maxPaths = 1) == it.identity.containerStamp
        }

        private fun discover(): RuntimeGroovySdk? = try {
            val sdkClass = GroovyObject::class.java
            val loader = sdkClass.classLoader
            val path = sdkClass.protectionDomain?.codeSource?.location?.toURI()
                ?.takeIf { it.scheme == "file" }
                ?.let { Path.of(it).toRealPath() }
            path?.let { discoverFrom(loader, it) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun discoverFrom(loader: ClassLoader, path: Path): RuntimeGroovySdk? {
            val container = path.toUri()
            val containerStamp = NativeArtifactStamp.observe(path, maxPaths = 1)
                ?.takeIf { it.files == null && it.size <= RuntimeGroovySdkArchive.MAX_CONTAINER_BYTES }
            val indexed = containerStamp?.let { packaged(loader, container) ?: RuntimeGroovySdkArchive.unshaded(path) }
            val stableStamp = containerStamp?.takeIf {
                indexed != null && NativeArtifactStamp.observe(path, maxPaths = 1) == it
            }
            val identity = if (stableStamp != null && indexed != null) {
                NativeCompilerSdkIdentity(
                    componentId = indexed.first,
                    container = container,
                    containerStamp = stableStamp,
                    indexDigest = RuntimeGroovySdkArchive.indexDigest(indexed.second),
                )
            } else {
                null
            }
            return identity?.let { trustedIdentity ->
                RuntimeGroovySdk(trustedIdentity, loader, Collections.unmodifiableMap(checkNotNull(indexed).second))
                    .takeIf { sdk -> sdk.resource("groovy/lang/GroovyObject.class") != null }
            }
        }

        private fun packaged(loader: ClassLoader, expectedContainer: URI): Pair<String, Map<String, String>>? {
            val resources = packagedResources(loader, expectedContainer) ?: return null
            val metadata = RuntimeGroovySdkArchive.properties(RuntimeGroovySdkArchive.open(resources.first))
            val validMetadata =
                metadata.getProperty("format") == "1" &&
                    metadata.getProperty("module") == "groovy" &&
                    metadata.getProperty("version") == GroovySystem.getVersion() &&
                    metadata.getProperty("group") in setOf("org.apache.groovy", "org.codehaus.groovy")
            val entries = if (validMetadata) {
                RuntimeGroovySdkArchive.indexedClasses(
                    RuntimeGroovySdkArchive.properties(RuntimeGroovySdkArchive.open(resources.second)),
                )
            } else {
                null
            }
            val id = metadata.getProperty("group") + ":groovy:" + metadata.getProperty("version")
            return entries?.let { id to it }
        }

        private fun packagedResources(loader: ClassLoader, expectedContainer: URI): Pair<URL, URL>? {
            val manifest = loader.getResource(RuntimeGroovySdkArchive.SOURCE_INDEX_PREFIX + "groovy.properties")
                ?.takeIf { resource -> RuntimeGroovySdkArchive.container(resource) == expectedContainer }
                ?: return null
            val index = loader.getResource(
                RuntimeGroovySdkArchive.SOURCE_INDEX_PREFIX + "groovy-class-digests.properties",
            )
                ?.takeIf { resource -> RuntimeGroovySdkArchive.container(resource) == expectedContainer }
                ?: return null
            return manifest to index
        }
    }
}

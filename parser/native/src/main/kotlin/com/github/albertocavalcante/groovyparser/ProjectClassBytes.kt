// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.BinaryEntry
import com.github.albertocavalcante.nativeapi.NativeBinaryInputs
import com.github.albertocavalcante.nativeapi.classDigest
import com.github.albertocavalcante.nativeapi.openBinaryJar
import java.io.IOException
import java.io.InputStream
import java.net.JarURLConnection
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.file.Files
import java.nio.file.Path
import java.security.CodeSigner
import java.security.CodeSource
import java.util.jar.Manifest

/** JDK lookup selects the resource. Local files supply one immutable payload for ASM and JVM definition. */
internal class ProjectClassBytes(private val urls: () -> Array<URL>, private val find: (String) -> URL?) {
    private val entries = linkedMapOf<String, BinaryEntry?>()
    private val payloads = mutableMapOf<String, Payload>()
    private var bytesHeld = 0L
    private var nameCharacters = 0L
    private var retired = false
    private var complete = true
    private var closed = false
    private var roots: List<URI>? = null

    sealed interface Selection {
        data object Missing : Selection
        class Ordinary(val url: URL?) : Selection
    }

    class Payload(
        val bytes: ByteArray,
        val source: CodeSource,
        val manifest: Manifest?,
        val identity: BinaryEntry,
    ) : Selection {
        // Groovy 4 caches decompiled stubs by URI. Equal keys must always serve equal bytes.
        val metadataResource: URL = URL(null, "gvy-class:/${identity.digest}.class", object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = object : URLConnection(url) {
                override fun connect() = Unit
                override fun getInputStream(): InputStream = bytes.inputStream()
                override fun getContentLength(): Int = bytes.size
            }
        })
    }

    @Synchronized
    fun lookup(name: String): Selection {
        if (closed) return Selection.Missing
        payloads[name]?.let { return it }
        val selected = find(name)
        val path = urls().map { it.toURI() }
        if (!canTrack(path, name)) return ordinary(selected)
        if (selected == null) {
            if (path.isNotEmpty()) entries[name] = null
            return Selection.Missing
        }
        val payload = try {
            when (selected.protocol) {
                "file" -> directory(path, selected, name)
                "jar" -> archive(selected, name)
                else -> null
            }
        } catch (_: IOException) {
            null
        }
        if (payload == null || payload.identity.root !in path) return ordinary(selected)
        if (entries.containsKey(name)) {
            // A previously absent class appeared while this owner was in use.
            retired = true
        }
        bytesHeld += payload.bytes.size
        entries[name] = payload.identity
        payloads[name] = payload
        return payload
    }

    private fun canTrack(path: List<URI>, name: String): Boolean {
        val known = roots
        if (known == null) roots = path
        if (known != null && known != path) retired = true
        if (path.isNotEmpty() && name !in entries) nameCharacters += name.length
        return !retired && path.all { it.scheme == "file" && it.authority == null } &&
            name.length <= MAX_NAME && !name.startsWith("/") && name.split('/').none { it == "." || it == ".." } &&
            entries.size < MAX_NAMES && nameCharacters <= MAX_NAME_CHARACTERS
    }

    private fun ordinary(selected: URL?): Selection.Ordinary {
        complete = false
        return Selection.Ordinary(selected)
    }

    private fun directory(path: List<URI>, selected: URL, name: String): Payload? {
        val file = Path.of(selected.toURI())
        val root = path.firstOrNull { it.path.endsWith("/") && Path.of(it).resolve(name) == file } ?: return null
        val actual = file.toRealPath()
        val size = Files.size(actual)
        val bytes = read(size) { Files.newInputStream(actual) } ?: return null
        val source = CodeSource(root.toURL(), null as Array<CodeSigner>?)
        return Payload(bytes, source, null, BinaryEntry(root, actual, null, bytes.size, classDigest(bytes)))
    }

    private fun archive(selected: URL, name: String): Payload? {
        val connection = selected.openConnection() as? JarURLConnection ?: return null
        val root = connection.jarFileURL.toURI()
        if (root.scheme != "file") return null
        val file = Path.of(root).toRealPath()
        return openBinaryJar(file).use { jar ->
            val entry = jar.getJarEntry(name) ?: return null
            if (connection.entryName != name && connection.entryName != entry.realName) return null
            val bytes = read(entry.size) { jar.getInputStream(entry) } ?: return null
            // The verifier populates signers only after consuming the entire signed entry.
            val source = CodeSource(root.toURL(), entry.codeSigners)
            val identity = BinaryEntry(root, file, entry.realName, bytes.size, classDigest(bytes))
            Payload(bytes, source, jar.manifest, identity)
        }
    }

    private fun read(size: Long, open: () -> InputStream): ByteArray? {
        if (size < 0 || size > MAX_ENTRY_BYTES || size + bytesHeld > MAX_OWNER_BYTES) return null
        return open().use { it.readNBytes(size.toInt() + 1) }.takeIf { it.size.toLong() == size }
    }

    @Synchronized
    fun unavailable() {
        complete = false
    }

    @Synchronized
    fun snapshot(): NativeBinaryInputs {
        val path = roots
        val differentPath = path != null && path != urls().map { it.toURI() }
        return NativeBinaryInputs(path.orEmpty(), entries.toMap(), complete, retired || differentPath)
    }

    @Synchronized
    fun close() {
        closed = true
        payloads.clear()
    }

    private companion object {
        const val MAX_NAME = 4096
        const val MAX_NAMES = 4096
        const val MAX_NAME_CHARACTERS = 512 * 1024
        const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
        const val MAX_OWNER_BYTES = 16 * 1024 * 1024
    }
}

// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.nativeapi

import com.github.albertocavalcante.groovyparser.SdkFallbackGroovyClassLoader
import groovy.lang.GroovyClassLoader
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.zip.ZipFile

/** The ordered search path and results consumed by one native owner. Contains no loader, AST, or open archive. */
class NativeBinaryInputs internal constructor(
    private val roots: List<URI>,
    private val entries: Map<String, BinaryEntry?>,
    private val complete: Boolean,
    private val retired: Boolean,
) {
    val isolated: Boolean get() = complete && !retired && entries.isEmpty()

    fun check(checkCancelled: () -> Unit): NativeSourceMembership.Status {
        checkCancelled()
        if (retired) return NativeSourceMembership.Status.CHANGED
        if (!complete) return NativeSourceMembership.Status.UNAVAILABLE
        return try {
            if (BinaryPathCheck(entries, checkCancelled).matches(roots)) {
                NativeSourceMembership.Status.CURRENT
            } else {
                NativeSourceMembership.Status.CHANGED
            }
        } catch (_: IOException) {
            NativeSourceMembership.Status.UNAVAILABLE
        } catch (_: SecurityException) {
            NativeSourceMembership.Status.UNAVAILABLE
        }
    }
}

fun GroovyClassLoader.nativeBinaryInputs(): NativeBinaryInputs? =
    (this as? SdkFallbackGroovyClassLoader)?.binaryInputs()

internal data class BinaryEntry(val root: URI, val file: Path, val entry: String?, val size: Int, val digest: String)

internal fun classDigest(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** Reopen local containers once per check. Only entries on the search prefix are probed. */
private class BinaryPathCheck(entries: Map<String, BinaryEntry?>, private val checkCancelled: () -> Unit) {
    private val remaining = entries.toMutableMap()

    fun matches(roots: List<URI>): Boolean {
        for (root in roots) {
            if (remaining.isEmpty()) break
            checkCancelled()
            val file = Path.of(root)
            val unchanged = if (root.path.endsWith("/")) {
                scan { name, before -> directoryEntry(root, file.resolve(name), before) }
            } else {
                archive(root, file)
            }
            if (!unchanged) return false
        }
        return remaining.values.all { it == null }
    }

    /** Consume each selected name at its first hit. A missing candidate stays pending for later URLs. */
    private fun scan(match: (String, BinaryEntry?) -> Boolean?): Boolean {
        val iterator = remaining.entries.iterator()
        while (iterator.hasNext()) {
            checkCancelled()
            val (name, before) = iterator.next()
            when (match(name, before)) {
                false -> return false
                true -> iterator.remove()
                null -> Unit
            }
        }
        return true
    }

    private fun directoryEntry(root: URI, file: Path, before: BinaryEntry?): Boolean? {
        val attributes = try {
            Files.readAttributes(file, BasicFileAttributes::class.java)
        } catch (_: NoSuchFileException) {
            return null
        } catch (_: NotDirectoryException) {
            return null
        }
        if (!attributes.isRegularFile) return null
        if (before == null || root != before.root || file.toRealPath() != before.file ||
            before.entry != null || attributes.size() != before.size.toLong()
        ) {
            return false
        }
        return Files.newInputStream(file).use { classDigest(it.readNBytes(before.size + 1)) == before.digest }
    }

    private fun archive(root: URI, file: Path): Boolean {
        val actual = try {
            file.toRealPath()
        } catch (_: NoSuchFileException) {
            return true
        } catch (_: NotDirectoryException) {
            return true
        }
        return openBinaryJar(actual).use { jar ->
            val indirect by lazy { hasIndirectEntries(file, jar) }
            scan { name, before ->
                val member = jar.getJarEntry(name)
                when {
                    member != null -> matchesEntry(jar, member, before, root, actual)
                    indirect -> throw IOException("Indirect archive search cannot be verified for $file")
                    else -> null
                }
            }
        }
    }

    private fun matchesEntry(jar: JarFile, member: JarEntry, before: BinaryEntry?, root: URI, actual: Path): Boolean {
        if (before == null || root != before.root || actual != before.file ||
            member.realName != before.entry || member.size != before.size.toLong()
        ) {
            return false
        }
        return jar.getInputStream(member).use { classDigest(it.readNBytes(before.size + 1)) == before.digest }
    }

    private fun hasIndirectEntries(file: Path, jar: JarFile): Boolean {
        if (!jar.manifest?.mainAttributes?.getValue(Attributes.Name.CLASS_PATH).isNullOrBlank()) return true
        val index = jar.getJarEntry("META-INF/INDEX.LIST") ?: return false
        if (index.size !in 0..MAX_INDEX_BYTES.toLong()) return true
        val bytes = jar.getInputStream(index).use { it.readNBytes(MAX_INDEX_BYTES + 1) }
        if (bytes.size.toLong() != index.size) return true
        // A single-archive JarIndex, as shipped by Groovy, introduces no additional physical candidates.
        val jars = bytes.toString(Charsets.UTF_8).lineSequence().filter { it.endsWith(".jar") }
        return jars.singleOrNull() != file.fileName.toString()
    }

    private companion object {
        const val MAX_INDEX_BYTES = 256 * 1024
    }
}

internal fun openBinaryJar(path: Path) = JarFile(path.toFile(), true, ZipFile.OPEN_READ, JarFile.runtimeVersion())

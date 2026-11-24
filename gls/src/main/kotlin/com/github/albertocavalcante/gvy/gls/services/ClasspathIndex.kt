// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.UTFDataFormatException
import java.io.UncheckedIOException
import java.lang.module.ModuleReader
import java.lang.module.ModuleReference
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.EnumMap
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo

data class IndexedClass(val simpleName: String, val fullName: String)

interface ClasspathIndex {
    fun index(classpathEntries: List<String>): List<IndexedClass>
}

/**
 * Lists the public classes of the classpath entries (jars, zips and class directories) and of the JDK modules the
 * running JVM resolved.
 *
 * Only names are wanted, so each class file is read as far as its `this_class` and `access_flags` (JVMS §4.1) and
 * then dropped. A full ClassGraph scan builds a linked `ClassInfo` graph for every class and holds it until the
 * scan closes; for the 47 thousand classes of the gls test classpath and JDK that peaked above 256 MiB and, with
 * the suite's own live set, exhausted the 1 GiB test JVM (#2186). The build is single-threaded and runs on the
 * first completion request, where ClassGraph used several threads; see the commit that introduced this for timings.
 *
 * Differences from the ClassGraph scan it replaces:
 * - Nested classes are listed by their own name (`java.util.Map$Entry` is `Entry`), exactly as before.
 * - An entry counts only when the class's own `this_class` equals its path, so `BOOT-INF/classes/...` entries and
 *   stray paths are skipped, as ClassGraph did.
 * - Classes under `META-INF/versions/N/` are listed under their real name when N is at most the running feature
 *   release. A base copy and versioned copies of one name count once, and the first copy in archive order wins, not
 *   the highest N; the `Multi-Release` manifest attribute is not consulted. An unreadable or non-public first copy
 *   yields to a later one.
 * - Only `.jar` and `.zip` archives and directories are read; nested jars (`BOOT-INF/lib`), `.war`, and jars that
 *   are only named by another jar's manifest `Class-Path` are not.
 * - `java.lang.Object` is listed, which ClassGraph left out.
 *
 * Work is bounded: a class file over [MAX_CLASS_FILE_BYTES] and an archive over [MAX_ARCHIVE_ENTRIES] entries are
 * skipped, and a bad entry, archive, directory or module is skipped without ending the build. Skips are counted by
 * reason and reported in one warning per build.
 */
class JvmClasspathIndex : ClasspathIndex {

    override fun index(classpathEntries: List<String>): List<IndexedClass> {
        val names = ClassNames()
        classpathEntries.distinct().forEach { entry -> scanEntry(entry, names) }
        scanBootModules(names)

        names.skipped.let { skipped ->
            if (skipped.isNotEmpty()) {
                val summary = skipped.entries.joinToString { (reason, count) -> "${reason.label}: $count" }
                logger.warn { "Class index skipped entries ($summary)" }
            }
        }
        logger.debug { "JvmClasspathIndex produced ${names.size} classes" }
        return names.toList()
    }

    private fun scanEntry(entry: String, names: ClassNames) {
        try {
            val path = Paths.get(entry)
            when {
                path.isDirectory() -> scanDirectory(path, names)
                path.extension.lowercase() in ARCHIVE_EXTENSIONS -> scanArchive(path, names)
            }
        } catch (e: InvalidPathException) {
            logger.debug(e) { "Malformed classpath entry $entry" }
            names.skip(SkipReason.UNREADABLE)
        } catch (e: IOException) {
            logger.debug(e) { "Unreadable classpath entry $entry" }
            names.skip(SkipReason.UNREADABLE)
        } catch (e: UncheckedIOException) {
            logger.debug(e) { "Unreadable classpath entry $entry" }
            names.skip(SkipReason.UNREADABLE)
        }
    }

    private fun scanDirectory(root: Path, names: ClassNames) {
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { file ->
                try {
                    val size = Files.size(file)
                    names.add(file.relativeTo(root).joinToString("/"), size) { Files.newInputStream(file) }
                } catch (e: IOException) {
                    logger.debug(e) { "Unreadable class file $file" }
                    names.skip(SkipReason.UNREADABLE)
                }
            }
        }
    }

    private fun scanArchive(archive: Path, names: ClassNames) {
        ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries()
            var seen = 0
            while (entries.hasMoreElements()) {
                if (++seen > MAX_ARCHIVE_ENTRIES) {
                    names.skip(SkipReason.TOO_MANY_ENTRIES)
                    return
                }
                val entry = entries.nextElement()
                if (!entry.isDirectory) names.add(entry.name, entry.size) { zip.getInputStream(entry) }
            }
        }
    }

    /** The same module set a default ClassGraph scan sees: what the boot layer resolved, not every system module. */
    private fun scanBootModules(names: ClassNames) {
        ModuleLayer.boot().configuration().modules().forEach { scanModule(it.reference(), names) }
    }

    private fun scanModule(module: ModuleReference, names: ClassNames) {
        try {
            module.open().use { scanReader(it, names) }
        } catch (e: IOException) {
            logger.debug(e) { "Unreadable module ${module.descriptor().name()}" }
            names.skip(SkipReason.UNREADABLE)
        } catch (e: UncheckedIOException) {
            logger.debug(e) { "Unreadable module ${module.descriptor().name()}" }
            names.skip(SkipReason.UNREADABLE)
        }
    }

    private fun scanReader(reader: ModuleReader, names: ClassNames) {
        reader.list().use { resources ->
            resources.forEach { name ->
                names.add(name, UNKNOWN_SIZE) { reader.open(name).orElseThrow { IOException("Missing $name") } }
            }
        }
    }

    /** Accumulates the first public, non-synthetic class seen under each qualified name. */
    private class ClassNames {
        private val found = LinkedHashMap<String, IndexedClass>()
        private val buffer = ByteArray(BUFFER_BYTES)
        val skipped = EnumMap<SkipReason, Int>(SkipReason::class.java)

        val size: Int get() = found.size

        fun toList(): List<IndexedClass> = found.values.toList()

        fun skip(reason: SkipReason) {
            skipped.merge(reason, 1, Int::plus)
        }

        fun add(resource: String, declaredSize: Long, openClassFile: () -> InputStream) {
            val fullName = classNameOf(resource) ?: return
            if (fullName in found) return
            if (declaredSize > MAX_CLASS_FILE_BYTES) return skip(SkipReason.OVERSIZED)
            val expected = internalName(fullName) ?: return skip(SkipReason.NAME_MISMATCH)
            val read = try {
                openClassFile().use {
                    readClassFile(ClassFileCursor(it, buffer, MAX_CLASS_FILE_BYTES), expected)
                }
            } catch (e: ClassFileTooLargeException) {
                logger.debug(e) { "Oversized class $resource" }
                return skip(SkipReason.OVERSIZED)
            } catch (e: IOException) {
                logger.debug(e) { "Unreadable class $resource" }
                return skip(SkipReason.UNREADABLE)
            } catch (e: UncheckedIOException) {
                logger.debug(e) { "Unreadable class $resource" }
                return skip(SkipReason.UNREADABLE)
            }
            when (read) {
                is ClassFileRead.Rejected -> skip(read.reason)
                is ClassFileRead.Accepted -> if (read.flags.isIndexable) {
                    // A nested class is listed by its own name, as `Map.Entry` is `Entry`.
                    found[fullName] = IndexedClass(fullName.substringAfterLast('.').substringAfterLast('$'), fullName)
                }
            }
        }

        /** The name as the constant pool spells it, in modified UTF-8 (JVMS §4.4.7); null if it cannot be encoded. */
        private fun internalName(fullName: String): ByteArray? {
            val bytes = ByteArrayOutputStream()
            try {
                DataOutputStream(bytes).writeUTF(fullName.replace('.', '/'))
            } catch (_: UTFDataFormatException) {
                return null // longer than a constant pool entry can be, so no class has it
            }
            return bytes.toByteArray().copyOfRange(UTF_LENGTH_PREFIX, bytes.size())
        }
    }

    internal companion object {
        private val logger = KotlinLogging.logger {}

        /** A class file larger than this is skipped, and no read goes past it. */
        const val MAX_CLASS_FILE_BYTES = 16L * 1024 * 1024

        /** An archive is read only up to this many entries. */
        const val MAX_ARCHIVE_ENTRIES = 1_000_000

        private const val UNKNOWN_SIZE = -1L
        private const val BUFFER_BYTES = 8192
        private const val UTF_LENGTH_PREFIX = 2
        private const val CLASS_SUFFIX = ".class"
        private const val META_INF = "META-INF/"
        private const val VERSIONS = "META-INF/versions/"
        private val DESCRIPTOR_FILES = setOf("module-info.class", "package-info.class")
        private val ARCHIVE_EXTENSIONS = setOf("jar", "zip")

        /**
         * The qualified class name an archive resource path stands for, or null when it is not a class a Java
         * program can load from this runtime: a descriptor, another META-INF resource, or a versioned copy for
         * a release newer than the running one.
         */
        internal fun classNameOf(resource: String): String? {
            if (!resource.endsWith(CLASS_SUFFIX) || resource.substringAfterLast('/') in DESCRIPTOR_FILES) return null
            val path = when {
                resource.startsWith(VERSIONS) -> versionedPath(resource) ?: return null
                resource.startsWith(META_INF) -> return null
                else -> resource
            }
            return path.removeSuffix(CLASS_SUFFIX).replace('/', '.')
        }

        private fun versionedPath(resource: String): String? {
            val afterPrefix = resource.removePrefix(VERSIONS)
            val release = afterPrefix.substringBefore('/').toIntOrNull() ?: return null
            if (release > Runtime.version().feature() || !afterPrefix.contains('/')) return null
            return afterPrefix.substringAfter('/')
        }
    }
}

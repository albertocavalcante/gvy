// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/** Qualifies the exact runtime files observed on Gradle classpaths. */
internal object GradleExecutionRuntimeInspector {
    private data class RuntimeScan(
        val identified: MutableList<Pair<Path, String>> = mutableListOf(),
        val cores: MutableList<Path> = mutableListOf(),
    )

    fun inspect(
        classpath: List<Path>,
        artifacts: List<GradleClasspathEntryModel?>?,
        compileClasspath: List<Path>,
    ): GradleExecutionRuntime {
        val scan = RuntimeScan()
        val metadata = requireNotNull(artifacts).associateBy { path(requireNotNull(it).path) }
        val failure = classpath.firstNotNullOfOrNull { entry -> inspectRuntimeEntry(entry, metadata[entry], scan) }
        if (failure != null) return failure
        return completeRuntime(scan, compileClasspath)
    }

    private fun completeRuntime(scan: RuntimeScan, compileClasspath: List<Path>): GradleExecutionRuntime {
        val conflicting = scan.cores.size > 1 || scan.identified.map { it.second }.distinct().size > 1
        val core = scan.cores.singleOrNull()
        val version = scan.identified.firstOrNull { it.first == core }?.second
        return when {
            conflicting -> GradleExecutionRuntime.Conflict
            core == null || version == null -> GradleExecutionRuntime.Unknown
            else -> inspectCompileClasspath(compileClasspath, core)
                ?: GradleExecutionRuntime.Established(version, scan.identified.map { it.first })
        }
    }

    private fun inspectRuntimeEntry(
        entry: Path,
        metadata: GradleClasspathEntryModel?,
        scan: RuntimeScan,
    ): GradleExecutionRuntime? = when {
        containsDirectoryCore(entry) -> GradleExecutionRuntime.Unknown
        !isJar(entry) -> null
        else -> inspectRuntimeJar(entry, metadata, scan)
    }

    private fun inspectRuntimeJar(
        entry: Path,
        metadata: GradleClasspathEntryModel?,
        scan: RuntimeScan,
    ): GradleExecutionRuntime? = try {
        JarFile(entry.toFile()).use { jar ->
            val core = jar.getJarEntry(CORE_CLASS) != null
            if (core) scan.cores.add(entry)
            val candidates = metadata?.coordinateCandidates.orEmpty().filterNotNull().filter(::isGroovyModule)
            val candidate = candidates.singleOrNull()
            when {
                candidates.size > 1 -> GradleExecutionRuntime.Conflict
                core && candidate == null -> GradleExecutionRuntime.Unknown
                else -> recordCandidate(entry, candidate, scan)
            }
        }
    } catch (_: java.io.IOException) {
        GradleExecutionRuntime.Unknown
    }

    private fun recordCandidate(
        entry: Path,
        candidate: GradleModuleCoordinatesModel?,
        scan: RuntimeScan,
    ): GradleExecutionRuntime? {
        if (candidate != null) scan.identified += entry to requireNotNull(candidate.version)
        return null
    }

    private fun isGroovyModule(coordinates: GradleModuleCoordinatesModel): Boolean =
        coordinates.group in setOf("org.codehaus.groovy", "org.apache.groovy") &&
            (
                coordinates.module == "groovy" || coordinates.module == "groovy-all" ||
                    coordinates.module?.startsWith("groovy-") == true
                )

    private fun inspectCompileClasspath(classpath: List<Path>, runtimeCore: Path): GradleExecutionRuntime? =
        classpath.firstNotNullOfOrNull { entry -> inspectCompileEntry(entry, runtimeCore) }

    private fun inspectCompileEntry(entry: Path, runtimeCore: Path): GradleExecutionRuntime? = when {
        containsDirectoryCore(entry) -> GradleExecutionRuntime.Unknown
        !isJar(entry) -> null
        else -> inspectCompileJar(entry, runtimeCore)
    }

    private fun inspectCompileJar(entry: Path, runtimeCore: Path): GradleExecutionRuntime? = try {
        JarFile(entry.toFile()).use { jar ->
            if (jar.getJarEntry(CORE_CLASS) != null && entry != runtimeCore) {
                GradleExecutionRuntime.Conflict
            } else {
                null
            }
        }
    } catch (_: java.io.IOException) {
        GradleExecutionRuntime.Unknown
    }

    private fun path(value: String?): Path = Path.of(requireNotNull(value)).also { require(it.isAbsolute) }

    private fun containsDirectoryCore(path: Path): Boolean =
        Files.isDirectory(path) && Files.exists(path.resolve(CORE_CLASS))

    private fun isJar(path: Path): Boolean =
        Files.isRegularFile(path) && path.toString().endsWith(".jar", ignoreCase = true)

    private const val CORE_CLASS = "groovy/lang/GroovySystem.class"
}

// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest

/** A fresh observation of a source set, not a promise that its inputs cannot change. */
data class GradleExecutionContext(
    val targetId: URI,
    val buildDirectory: Path,
    val projectDirectory: Path,
    val projectPath: String,
    val sourceSet: String,
    val sourceFiles: Set<Path>,
    val inputFiles: Set<Path>,
    val compileClasspath: List<Path>,
    val runtimeClasspath: List<Path>,
    val outputDirectories: List<Path>,
    val preparationTask: String,
    val complete: Boolean,
    val runtime: GradleExecutionRuntime,
    val generation: String,
)

sealed interface GradleExecutionRuntime {
    data object Unknown : GradleExecutionRuntime
    data object Conflict : GradleExecutionRuntime
    data class Established(val version: String, val jars: List<Path>) : GradleExecutionRuntime
}

internal object GradleExecutionProjection {
    fun project(model: GradleExecutionModel): List<GradleExecutionContext> {
        require(model.enumerationComplete == true) { "Gradle compiler task enumeration is incomplete" }
        val buildDirectory = path(model.buildDirectory)
        return requireNotNull(model.targets).map { value ->
            val target = requireNotNull(value)
            require(target.state in setOf("COMPLETE", "UNRESOLVED"))
            require(target.executionState in setOf("COMPLETE", "UNRESOLVED"))
            val complete = target.state == "COMPLETE" && target.executionState == "COMPLETE"
            val projectPath = requireNotNull(target.projectPath).also { require(it.startsWith(':')) }
            val projectDirectory = path(target.projectDirectory)
            val sourceSet = requireNotNull(target.sourceSet).also { require(it.isNotBlank()) }
            val sourceFiles = paths(target.sourceFiles).toSet()
            val inputFiles = paths(target.inputFiles).toSet() + sourceFiles
            val compile = if (complete) {
                requireNotNull(target.artifacts).map { path(requireNotNull(it).path) }
            } else {
                emptyList()
            }
            val runtimeClasspath = if (complete) paths(target.runtimeClasspath) else emptyList()
            val outputs = target.outputDirectories?.let(::paths) ?: emptyList()
            val task = requireNotNull(target.preparationTask).also { require(it.startsWith(':')) }
            val runtime = if (complete) {
                GradleExecutionRuntimeInspector.inspect(runtimeClasspath, target.runtimeArtifacts, compile)
            } else {
                GradleExecutionRuntime.Unknown
            }
            val components = listOf(buildDirectory.toString(), projectPath, sourceSet)
            val id = URI("gvy-gradle", components.joinToString("") { "${it.length}:$it" }, null)
            val facts = listOf(id.toString(), projectDirectory.toString(), task, complete.toString()) +
                sourceFiles.map(Path::toString).sorted() + inputFiles.map(Path::toString).sorted() +
                compile.map(Path::toString) +
                runtimeClasspath.map(Path::toString) + outputs.map(Path::toString) + runtime.toString()
            val digest = MessageDigest.getInstance("SHA-256")
            facts.forEach { fact ->
                val bytes = fact.toByteArray(Charsets.UTF_8)
                digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8))
                digest.update(bytes)
            }
            val generation = digest.digest().joinToString("") { "%02x".format(it) }
            GradleExecutionContext(
                id, buildDirectory, projectDirectory, projectPath, sourceSet, sourceFiles, inputFiles, compile,
                runtimeClasspath, outputs, task, complete, runtime, generation,
            )
        }.also { contexts -> require(contexts.map { it.targetId }.distinct().size == contexts.size) }
    }

    private fun paths(values: List<String?>?): List<Path> = requireNotNull(values).map(::path)

    private fun path(value: String?): Path = Path.of(requireNotNull(value)).also { require(it.isAbsolute) }
}

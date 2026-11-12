// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

object WorkspaceFixture {
    private val logger = KotlinLogging.logger {}

    fun materialize(scenarioSource: Path, fixtureName: String?): Path =
        materialize(scenarioSource, fixtureName, { Files.createTempDirectory("groovy-lsp-e2e-") })

    internal fun materialize(
        scenarioSource: Path,
        fixtureName: String?,
        createDirectory: () -> Path,
        copyFixture: (Path, Path) -> Unit = ::copyDirectory,
    ): Path {
        val fixture = fixtureName?.let { findFixture(scenarioSource, it) }
        val tempDir = createDirectory()
        var retained = false
        AutoCloseable { if (!retained) cleanup(tempDir) }.use {
            if (fixture != null) {
                copyFixture(fixture, tempDir)
                logger.info { "Materialized fixture '$fixtureName' into $tempDir" }
            }
            retained = true
            return tempDir
        }
    }

    fun cleanup(path: Path) {
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun findFixture(scenarioSource: Path, fixtureName: String): Path {
        var root = scenarioSource.parent
        while (root != null) {
            for (directory in listOf("workspaces", "fixtures")) {
                val candidate = root.resolve(directory).resolve(fixtureName)
                if (candidate.exists()) {
                    require(candidate.isDirectory()) {
                        "Fixture '$fixtureName' referenced by $scenarioSource is not a directory: $candidate"
                    }
                    return candidate
                }
            }
            root = root.parent
        }
        throw IllegalArgumentException("Could not find fixture '$fixtureName' in hierarchy of $scenarioSource")
    }

    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                val relative = source.relativize(path)
                val destination = target.resolve(relative.toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    destination.parent?.let(Files::createDirectories)
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}

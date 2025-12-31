package com.github.albertocavalcante.groovylsp.e2e

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

object WorkspaceFixture {
    private val logger = LoggerFactory.getLogger(WorkspaceFixture::class.java)

    fun materialize(scenarioSource: Path, fixtureName: String?): Path {
        val tempDir = Files.createTempDirectory("groovy-lsp-e2e-")

        if (fixtureName != null) {
            var root = scenarioSource.parent
            var fixturesDir: Path? = null

            // Search up the tree for 'workspaces' or 'fixtures'
            while (root != null) {
                val workspaces = root.resolve("workspaces")
                if (workspaces.exists() && workspaces.resolve(fixtureName).exists()) {
                    fixturesDir = workspaces
                    break
                }

                val fixtures = root.resolve("fixtures")
                if (fixtures.exists() && fixtures.resolve(fixtureName).exists()) {
                    fixturesDir = fixtures
                    break
                }
                root = root.parent
            }

            requireNotNull(fixturesDir) {
                "Could not find 'workspaces' or 'fixtures' directory in hierarchy of $scenarioSource"
            }

            val fixturePath = fixturesDir.resolve(fixtureName)
            require(fixturePath.exists() && fixturePath.isDirectory()) {
                "Fixture '$fixtureName' referenced by $scenarioSource not found at $fixturePath"
            }
            copyDirectory(fixturePath, tempDir)
            logger.info("Materialized fixture '{}' into {}", fixtureName, tempDir)
            Files.list(tempDir).use { stream ->
                stream.forEach { logger.info("Fixture root entry: {}", it.fileName) }
            }
        }

        return tempDir
    }

    fun cleanup(path: Path) {
        try {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        } catch (ex: IOException) {
            logger.warn("Failed to delete temporary workspace at {}", path, ex)
        }
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

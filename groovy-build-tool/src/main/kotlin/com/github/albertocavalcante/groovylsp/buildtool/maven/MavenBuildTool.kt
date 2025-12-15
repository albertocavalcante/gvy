package com.github.albertocavalcante.groovylsp.buildtool.maven

import com.github.albertocavalcante.groovylsp.buildtool.BuildTool
import com.github.albertocavalcante.groovylsp.buildtool.WorkspaceResolution
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

class MavenBuildTool : BuildTool {
    private val logger = LoggerFactory.getLogger(MavenBuildTool::class.java)

    override val name: String = "Maven"

    override fun canHandle(workspaceRoot: Path): Boolean = workspaceRoot.resolve("pom.xml").exists()

    override fun createWatcher(
        coroutineScope: kotlinx.coroutines.CoroutineScope,
        onChange: (Path) -> Unit,
    ): com.github.albertocavalcante.groovylsp.buildtool.BuildToolFileWatcher =
        MavenBuildFileWatcher(coroutineScope, onChange)

    override fun resolve(workspaceRoot: Path, onProgress: ((String) -> Unit)?): WorkspaceResolution {
        if (!canHandle(workspaceRoot)) {
            return WorkspaceResolution(emptyList(), emptyList())
        }

        onProgress?.invoke("Resolving Maven dependencies...")
        logger.info("Resolving Maven dependencies for: $workspaceRoot")

        val cpFile = Files.createTempFile("mvn-classpath", ".txt")
        try {
            val mvnCommand = getMvnCommand(workspaceRoot)
            val command = listOf(
                mvnCommand,
                "dependency:build-classpath",
                "-Dmdep.outputFile=${cpFile.toAbsolutePath()}",
            )

            logger.debug("Running Maven command: $command")
            val processBuilder = ProcessBuilder(command)
            processBuilder.directory(workspaceRoot.toFile())
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()

            // Read output to log it (and avoid blocking if buffer fills)
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText()
            }

            val exitCode = process.waitFor()

            if (exitCode != 0) {
                logger.error("Maven dependency resolution failed. Output:\n$output")
                // Don't throw, just return empty so we don't crash LSP
                return WorkspaceResolution(emptyList(), emptyList())
            }

            val classpathString = Files.readString(cpFile)
            val dependencies = classpathString.split(File.pathSeparator)
                .map { Paths.get(it.trim()) }
                .filter { it.exists() }

            // Standard Maven layout assumption for now
            val sourceDirs = listOf(
                workspaceRoot.resolve("src/main/java"),
                workspaceRoot.resolve("src/main/groovy"),
                workspaceRoot.resolve("src/test/java"),
                workspaceRoot.resolve("src/test/groovy"),
            ).filter { it.exists() }

            logger.info("Resolved ${dependencies.size} Maven dependencies")
            return WorkspaceResolution(dependencies, sourceDirs)
        } catch (e: Exception) {
            logger.error("Failed to resolve Maven dependencies", e)
            return WorkspaceResolution(emptyList(), emptyList())
        } finally {
            Files.deleteIfExists(cpFile)
        }
    }

    private fun getMvnCommand(workspaceRoot: Path): String {
        val mvnw = workspaceRoot.resolve("mvnw")
        if (mvnw.exists()) {
            // Ensure it's executable
            if (!Files.isExecutable(mvnw)) {
                mvnw.toFile().setExecutable(true)
            }
            return mvnw.toAbsolutePath().toString()
        }

        // Windows check
        val mvnwCmd = workspaceRoot.resolve("mvnw.cmd")
        if (mvnwCmd.exists()) {
            return mvnwCmd.toAbsolutePath().toString()
        }

        return "mvn"
    }
}

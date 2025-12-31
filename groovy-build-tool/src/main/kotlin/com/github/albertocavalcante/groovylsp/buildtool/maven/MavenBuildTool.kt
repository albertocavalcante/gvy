package com.github.albertocavalcante.groovylsp.buildtool.maven

import com.github.albertocavalcante.groovylsp.buildtool.BuildExecutableResolver
import com.github.albertocavalcante.groovylsp.buildtool.BuildTool
import com.github.albertocavalcante.groovylsp.buildtool.TestCommand
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

    override fun canHandle(workspaceRoot: Path): Boolean =
        MavenBuildFiles.fileNames.any { fileName -> workspaceRoot.resolve(fileName).exists() }

    override fun createWatcher(
        coroutineScope: kotlinx.coroutines.CoroutineScope,
        onChange: (Path) -> Unit,
    ): com.github.albertocavalcante.groovylsp.buildtool.BuildToolFileWatcher =
        MavenBuildFileWatcher(coroutineScope, onChange)

    private val dependencyResolver = MavenDependencyResolver()

    override fun resolve(workspaceRoot: Path, onProgress: ((String) -> Unit)?): WorkspaceResolution {
        if (!canHandle(workspaceRoot)) {
            return WorkspaceResolution(emptyList(), emptyList())
        }

        onProgress?.invoke("Resolving Maven dependencies...")
        logger.info("Resolving Maven dependencies for: $workspaceRoot")

        val pomPath = workspaceRoot.resolve("pom.xml")

        // Try embedded resolver first (faster, no subprocess)
        val embeddedDeps = tryEmbeddedResolution(pomPath)
        val dependencies = if (embeddedDeps.isNotEmpty()) {
            embeddedDeps
        } else {
            // Fallback to CLI-based resolution
            logger.info("Embedded resolution returned no results, falling back to CLI")
            resolveViaCli(workspaceRoot)
        }

        // Standard Maven layout assumption for now
        val sourceDirs = listOf(
            workspaceRoot.resolve("src/main/java"),
            workspaceRoot.resolve("src/main/groovy"),
            workspaceRoot.resolve("src/test/java"),
            workspaceRoot.resolve("src/test/groovy"),
        ).filter { it.exists() }

        // SPECIAL HANDLING: Jenkins Core Injection
        // If this is a Jenkins project (has Jenkinsfile) and jenkins-core is missing
        // (due to 'provided' scope), explicitly resolve and inject it.
        val finalDependencies = if (workspaceRoot.resolve("Jenkinsfile").exists()) {
            ensureJenkinsCore(dependencies, pomPath)
        } else {
            dependencies
        }

        logger.info("Resolved ${finalDependencies.size} Maven dependencies")
        return WorkspaceResolution(finalDependencies, sourceDirs)
    }

    private fun ensureJenkinsCore(dependencies: List<Path>, pomPath: Path): List<Path> {
        val hasJenkinsCore = dependencies.any {
            val name = it.fileName.toString()
            name.startsWith("jenkins-core-") && name.endsWith(".jar")
        }

        if (hasJenkinsCore) {
            logger.debug("jenkins-core already present in dependencies")
            return dependencies
        }

        logger.info("Jenkinsfile detected but jenkins-core missing (likely 'provided' scope). Attempting injection.")

        // Use repos from POM for resolution
        val model = try {
            val factory = org.apache.maven.model.building.DefaultModelBuilderFactory()
            val request = org.apache.maven.model.building.DefaultModelBuildingRequest().apply {
                pomFile = pomPath.toFile()
                validationLevel = org.apache.maven.model.building.ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
            }
            factory.newInstance().build(request).effectiveModel
        } catch (e: Exception) {
            null
        }

        val repositories = if (model != null) dependencyResolver.getRemoteRepositories(model) else emptyList()

        // Fallback to LTS version if not found in project (programmatic injection)
        val jenkinsCore = dependencyResolver.resolveArtifact(
            groupId = "org.jenkins-ci.main",
            artifactId = "jenkins-core",
            version = "2.440.1", // LTS baseline
            repositories = repositories,
        )

        return if (jenkinsCore != null) {
            logger.info("Injected jenkins-core support: $jenkinsCore")
            dependencies + jenkinsCore
        } else {
            logger.warn("Failed to inject jenkins-core; some Jenkins symbols may be unresolved")
            dependencies
        }
    }

    @Suppress("TooGenericExceptionCaught") // Catch-all for embedded resolution fallback
    private fun tryEmbeddedResolution(pomPath: Path): List<Path> = try {
        dependencyResolver.resolveDependencies(pomPath)
    } catch (e: Exception) {
        logger.warn("Embedded Maven resolution failed, will try CLI fallback", e)
        emptyList()
    }

    @Suppress("TooGenericExceptionCaught") // Catch-all for CLI process errors
    private fun resolveViaCli(workspaceRoot: Path): List<Path> {
        val cpFile = Files.createTempFile("mvn-classpath", ".txt")
        try {
            val mvnCommand = BuildExecutableResolver.resolveMaven(workspaceRoot)
            val command = listOf(
                mvnCommand,
                "dependency:build-classpath",
                "-DincludeScope=test",
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
                logger.error("Maven CLI dependency resolution failed. Output:\n$output")
                return emptyList()
            }

            val classpathString = Files.readString(cpFile)
            return classpathString.split(File.pathSeparator)
                .map { Paths.get(it.trim()) }
                .filter { it.exists() }
        } catch (e: Exception) {
            logger.error("Failed to resolve Maven dependencies via CLI", e)
            return emptyList()
        } finally {
            Files.deleteIfExists(cpFile)
        }
    }

    override fun getTestCommand(workspaceRoot: Path, suite: String, test: String?, debug: Boolean): TestCommand {
        val testArg = if (test != null) "$suite#$test" else suite
        val args = mutableListOf("test", "-Dtest=$testArg")

        if (debug) {
            args.add("-Dmaven.surefire.debug")
        }

        return TestCommand(
            executable = BuildExecutableResolver.resolveMaven(workspaceRoot),
            args = args,
            cwd = workspaceRoot.toString(),
        )
    }
}

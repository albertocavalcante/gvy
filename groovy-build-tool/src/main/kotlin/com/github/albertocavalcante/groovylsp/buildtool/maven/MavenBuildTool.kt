package com.github.albertocavalcante.groovylsp.buildtool.maven

import com.github.albertocavalcante.groovylsp.buildtool.BuildExecutableResolver
import com.github.albertocavalcante.groovylsp.buildtool.BuildTool
import com.github.albertocavalcante.groovylsp.buildtool.DependencyMetadata
import com.github.albertocavalcante.groovylsp.buildtool.TestCommand
import com.github.albertocavalcante.groovylsp.buildtool.WorkspaceResolution
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.maven.model.Model
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingRequest
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

class MavenBuildTool : BuildTool {
    private val logger = KotlinLogging.logger {}

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
        logger.info { "Resolving Maven dependencies for: $workspaceRoot" }

        val pomPath = workspaceRoot.resolve("pom.xml")

        // Try embedded resolver first (faster, no subprocess)
        val embeddedDeps = tryEmbeddedResolution(pomPath)
        val dependencies = if (embeddedDeps.isNotEmpty()) {
            embeddedDeps
        } else {
            // Fallback to CLI-based resolution
            logger.info { "Embedded resolution returned no results, falling back to CLI" }
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

        logger.info { "Resolved ${finalDependencies.size} Maven dependencies" }
        return WorkspaceResolution(finalDependencies, sourceDirs)
    }

    private fun ensureJenkinsCore(dependencies: List<Path>, pomPath: Path): List<Path> {
        val hasJenkinsCore = dependencies.any {
            val name = it.fileName.toString()
            name.startsWith("jenkins-core-") && name.endsWith(".jar")
        }

        if (hasJenkinsCore) {
            logger.debug { "jenkins-core already present in dependencies" }
            return dependencies
        }

        logger.info { "Jenkinsfile detected but jenkins-core missing (likely 'provided' scope). Attempting injection." }

        // Use repos from POM for resolution
        val model = runCatching {
            val factory = org.apache.maven.model.building.DefaultModelBuilderFactory()
            val request = org.apache.maven.model.building.DefaultModelBuildingRequest().apply {
                pomFile = pomPath.toFile()
                validationLevel = org.apache.maven.model.building.ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
            }
            factory.newInstance().build(request).effectiveModel
        }.onFailure { throwable ->
            if (throwable is Error) throw throwable
            logger.debug(throwable) { "Failed to build Maven model for Jenkins core injection; skipping injection" }
        }.getOrNull()

        val repositories = if (model != null) dependencyResolver.getRemoteRepositories(model) else emptyList()

        // Try to determine jenkins-core version from POM
        val jenkinsVersionFromPom = model?.let { m ->
            // Check dependencyManagement section first
            val fromDepMgmt = m.dependencyManagement?.dependencies?.find {
                it.groupId == "org.jenkins-ci.main" && it.artifactId == "jenkins-core"
            }?.version
            // Fall back to jenkins.version property
            fromDepMgmt ?: m.properties?.getProperty("jenkins.version")
        }

        val jenkinsVersion = jenkinsVersionFromPom ?: "2.440.1" // Fallback to LTS baseline
        logger.info { "Attempting to inject jenkins-core version: $jenkinsVersion" }

        // Resolve the artifact using the determined version
        val jenkinsCore = dependencyResolver.resolveArtifact(
            groupId = "org.jenkins-ci.main",
            artifactId = "jenkins-core",
            version = jenkinsVersion,
            repositories = repositories,
        )

        return if (jenkinsCore != null) {
            logger.info { "Injected jenkins-core support: $jenkinsCore" }
            dependencies + jenkinsCore
        } else {
            logger.warn { "Failed to inject jenkins-core; some Jenkins symbols may be unresolved" }
            dependencies
        }
    }

    private fun tryEmbeddedResolution(pomPath: Path): List<Path> =
        runCatching { dependencyResolver.resolveDependencies(pomPath) }
            .onFailure { throwable ->
                if (throwable is Error) throw throwable
                logger.warn(throwable) { "Embedded Maven resolution failed, will try CLI fallback" }
            }
            .getOrDefault(emptyList())

    private fun resolveViaCli(workspaceRoot: Path): List<Path> {
        val cpFile = Files.createTempFile("mvn-classpath", ".txt")
        var result: List<Path> = emptyList()
        try {
            val mvnCommand = BuildExecutableResolver.resolveMaven(workspaceRoot)
            val command = listOf(
                mvnCommand,
                "dependency:build-classpath",
                "-DincludeScope=test",
                "-Dmdep.outputFile=${cpFile.toAbsolutePath()}",
            )

            logger.debug { "Running Maven command: $command" }
            val processBuilder = ProcessBuilder(command)
            processBuilder.directory(workspaceRoot.toFile())
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()

            try {
                // Read output to log it (and avoid blocking if buffer fills)
                val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.readText()
                }

                val exitCode = process.waitFor()

                if (exitCode != 0) {
                    logger.error { "Maven CLI dependency resolution failed. Output:\n$output" }
                } else {
                    val classpathString = Files.readString(cpFile)
                    result = classpathString
                        .split(File.pathSeparator)
                        .mapNotNull { entry ->
                            runCatching { Paths.get(entry.trim()) }
                                .getOrNull()
                                ?.takeIf { it.exists() }
                        }
                }
            } finally {
                process.destroyForcibly()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.error(e) { "Maven CLI dependency resolution interrupted" }
        } catch (e: java.io.IOException) {
            logger.error(e) { "Failed to resolve Maven dependencies via CLI" }
        } catch (e: SecurityException) {
            logger.error(e) { "Failed to resolve Maven dependencies via CLI" }
        } finally {
            Files.deleteIfExists(cpFile)
        }

        return result
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

    override fun getCoverageCommand(workspaceRoot: Path, suite: String, test: String?): TestCommand {
        val testArg = if (test != null) "$suite#$test" else suite

        // Verify JaCoCo configuration to provide helpful feedback
        val pomPath = workspaceRoot.resolve("pom.xml")
        if (Files.exists(pomPath)) {
            runCatching { Files.readString(pomPath) }
                .onSuccess { pomContents ->
                    if (!pomContents.contains("jacoco-maven-plugin")) {
                        logger.warn {
                            "JaCoCo Maven plugin not detected in pom.xml at ${pomPath.toAbsolutePath()}. " +
                                "Coverage command may not generate coverage data. " +
                                "Ensure the jacoco-maven-plugin is configured with the prepare-agent goal."
                        }
                    }
                }
                .onFailure { throwable ->
                    logger.warn(throwable) { "Failed to read pom.xml at $pomPath to verify JaCoCo configuration" }
                }
        } else {
            logger.warn {
                "No pom.xml found at ${pomPath.toAbsolutePath()}. JaCoCo configuration cannot be verified; " +
                    "coverage command may not generate coverage data."
            }
        }

        val args = listOf("test", "-Dtest=$testArg", "jacoco:report")

        return TestCommand(
            executable = BuildExecutableResolver.resolveMaven(workspaceRoot),
            args = args,
            cwd = workspaceRoot.toString(),
        )
    }

    override fun getDependencyMetadata(workspaceRoot: Path): List<DependencyMetadata>? {
        val pomPath = workspaceRoot.resolve("pom.xml")
        if (!pomPath.exists()) return null

        return try {
            // 1. Parse POM to get declared (direct) dependencies with their metadata
            val model = parsePomForMetadata(pomPath) ?: return null

            // 2. Resolve all dependencies (including transitives) via existing resolver
            val resolvedJars = dependencyResolver.resolveDependencies(pomPath)

            // 3. Build metadata list from declared dependencies
            val metadataList = mutableListOf<DependencyMetadata>()

            // Add direct dependencies from POM (we have full metadata)
            model.dependencies.forEach { dep ->
                if (dep.version.isNullOrBlank()) return@forEach

                val coord = "${dep.groupId}:${dep.artifactId}"
                // Match JARs more precisely using artifactId-version prefix to avoid false matches
                // (e.g., prevents "commons-lang" from matching "commons-lang3-3.12.0.jar")
                val expectedPrefix = "${dep.artifactId}-${dep.version}"
                val jarPath = resolvedJars.find { jar ->
                    val fileName = jar.fileName.toString()
                    fileName.startsWith(expectedPrefix) && fileName.endsWith(".jar")
                }

                if (jarPath != null) {
                    metadataList.add(
                        DependencyMetadata(
                            name = coord,
                            version = dep.version,
                            scope = DependencyMetadata.normalizeScope(dep.scope),
                            path = jarPath.toUri().toString(),
                            isTransitive = false,
                        ),
                    )
                }
            }

            // Add transitive dependencies (JARs not matching direct deps)
            val addedPaths = metadataList.map { it.path }.toSet()
            resolvedJars.forEach { jarPath ->
                val pathUri = jarPath.toUri().toString()
                if (pathUri !in addedPaths) {
                    val (name, version) = DependencyMetadata.parseJarFileName(jarPath.fileName.toString())
                    metadataList.add(
                        DependencyMetadata(
                            name = name,
                            version = version,
                            scope = DependencyMetadata.SCOPE_RUNTIME, // Transitives are typically runtime
                            path = pathUri,
                            isTransitive = true,
                        ),
                    )
                }
            }

            // Deduplicate by path
            metadataList.distinctBy { it.path }
        } catch (e: Exception) {
            logger.error(e) { "Failed to extract Maven dependency metadata: ${e.message}" }
            null
        }
    }

    private fun parsePomForMetadata(pomPath: Path): Model? = try {
        val factory = DefaultModelBuilderFactory()
        val builder = factory.newInstance()
        val request = DefaultModelBuildingRequest().apply {
            pomFile = pomPath.toFile()
            validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
            isProcessPlugins = false
            isTwoPhaseBuilding = false
            systemProperties = System.getProperties()
        }
        builder.build(request).effectiveModel
    } catch (e: Exception) {
        logger.error { "Failed to parse POM for metadata: ${e.message}" }
        null
    }
}

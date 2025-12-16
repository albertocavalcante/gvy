package com.github.albertocavalcante.groovylsp.buildtool.maven

import com.github.albertocavalcante.groovylsp.buildtool.DependencyResolver
import org.apache.maven.model.Model
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingRequest
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.DependencyRequest
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Programmatic Maven dependency resolver using Maven Resolver (Aether).
 *
 * This replaces the CLI-based approach (`mvn dependency:build-classpath`) with
 * in-process resolution that:
 * - Respects `~/.m2/settings.xml` for local repository location
 * - Is cross-platform without needing `mvn`/`mvnw` detection
 * - Has no subprocess spawning overhead
 */
class MavenDependencyResolver : DependencyResolver {
    private val logger = LoggerFactory.getLogger(MavenDependencyResolver::class.java)

    override val name: String = "Maven Resolver"

    /**
     * Resolves all dependencies from a Maven project, including test scope.
     *
     * @param projectFile Path to the pom.xml file
     * @return List of resolved dependency JAR paths
     */
    override fun resolveDependencies(projectFile: Path): List<Path> {
        logger.info("Resolving dependencies using Maven Resolver for: $projectFile")

        if (!Files.exists(projectFile)) {
            logger.error("pom.xml not found at: $projectFile")
            return emptyList()
        }

        return try {
            val repositorySystem = AetherSessionFactory.repositorySystem
            val session = newRepositorySystemSession()

            // Parse the POM to get dependencies
            val model = parsePom(projectFile)
            if (model == null) {
                logger.error("Failed to parse pom.xml")
                return emptyList()
            }

            // Build collect request from dependencies
            val collectRequest = CollectRequest()
            collectRequest.repositories = getRemoteRepositories(model)

            model.dependencies.forEach { dep ->
                if (dep.version.isNullOrBlank()) {
                    logger.warn("Skipping dependency with missing version: {}:{}", dep.groupId, dep.artifactId)
                    return@forEach
                }
                val artifact = DefaultArtifact(
                    dep.groupId,
                    dep.artifactId,
                    dep.classifier ?: "",
                    dep.type ?: "jar",
                    dep.version,
                )
                // Include all scopes (compile, test, etc.)
                collectRequest.addDependency(Dependency(artifact, dep.scope ?: "compile"))
            }

            // Resolve dependencies
            val dependencyRequest = DependencyRequest(collectRequest, null)
            val result = repositorySystem.resolveDependencies(session, dependencyRequest)

            val dependencies = result.artifactResults
                .filter { it.isResolved }
                .mapNotNull { it.artifact?.file?.toPath() }

            logger.info("Resolved ${dependencies.size} dependencies via Maven Resolver")
            dependencies
        } catch (e: Exception) {
            logger.error("Failed to resolve dependencies with Maven Resolver", e)
            emptyList()
        }
    }

    private fun newRepositorySystemSession(): RepositorySystemSession {
        val localRepoPath = resolveLocalRepository() ?: AetherSessionFactory.resolveLocalRepository()
        return AetherSessionFactory.createSession(localRepoPath)
    }

    override fun resolveLocalRepository(): Path? {
        // 1. Check M2_REPO environment variable
        System.getenv("M2_REPO")?.let { envRepo ->
            val path = File(envRepo)
            if (path.exists()) {
                logger.debug("Using M2_REPO environment variable: $envRepo")
                return path.toPath()
            }
        }

        // 2. Check settings.xml for custom local repository
        val userHome = System.getProperty("user.home")
        val m2Dir = File(userHome, ".m2")
        val settingsFile = File(m2Dir, "settings.xml")

        if (settingsFile.exists()) {
            try {
                val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                dbf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
                val db = dbf.newDocumentBuilder()
                val nodeList = db.parse(settingsFile).getElementsByTagName("localRepository")
                if (nodeList.length > 0) {
                    val repoPath = nodeList.item(0).textContent.trim()
                    val customPath = File(repoPath)
                    if (customPath.exists()) {
                        logger.debug("Using localRepository from settings.xml: ${customPath.absolutePath}")
                        return customPath.toPath()
                    }
                }
            } catch (e: Exception) {
                logger.warn("Could not parse settings.xml: ${e.message}")
            }
        }

        // 3. Default location
        val defaultRepo = File(m2Dir, "repository")
        logger.debug("Using default Maven repository: ${defaultRepo.absolutePath}")
        return defaultRepo.toPath()
    }

    private fun parsePom(pomPath: Path): Model? = try {
        val factory = DefaultModelBuilderFactory()
        val builder = factory.newInstance()

        val request = DefaultModelBuildingRequest()
        request.pomFile = pomPath.toFile()
        request.validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
        request.isProcessPlugins = false
        request.isTwoPhaseBuilding = false
        request.systemProperties = System.getProperties()

        val result = builder.build(request)
        result.effectiveModel
    } catch (e: Exception) {
        logger.error("Failed to parse POM: ${e.message}")
        null
    }

    private fun getRemoteRepositories(model: Model): List<RemoteRepository> {
        val repos = mutableListOf<RemoteRepository>()

        // Always add Maven Central
        repos.add(
            RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2/")
                .build(),
        )

        // Add repositories from POM
        model.repositories.forEach { repo ->
            repos.add(
                RemoteRepository.Builder(repo.id, "default", repo.url)
                    .build(),
            )
        }

        return repos
    }
}

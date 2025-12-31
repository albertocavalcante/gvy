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

        @Suppress("TooGenericExceptionCaught") // Catch-all for resolution errors
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

    /**
     * Resolves a specific artifact by coordinates.
     *
     * @param groupId The group ID (e.g. "org.jenkins-ci.main")
     * @param artifactId The artifact ID (e.g. "jenkins-core")
     * @param version The version (e.g. "2.440.1")
     * @param repositories Optional list of repositories to search (defaults to Maven Central)
     * @return Path to the resolved JAR, or null if not found
     */
    fun resolveArtifact(
        groupId: String,
        artifactId: String,
        version: String,
        repositories: List<RemoteRepository> = emptyList(),
    ): Path? {
        logger.info("Resolving artifact: $groupId:$artifactId:$version")

        @Suppress("TooGenericExceptionCaught")
        return try {
            val repositorySystem = AetherSessionFactory.repositorySystem
            val session = newRepositorySystemSession()

            val artifact = DefaultArtifact(groupId, artifactId, "jar", version)
            val dependency = Dependency(artifact, "compile")

            val collectRequest = CollectRequest()
            collectRequest.root = dependency

            // Add default repositories (Central)
            collectRequest.repositories = if (repositories.isNotEmpty()) {
                repositories
            } else {
                listOf(
                    RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2/").build(),
                )
            }

            val dependencyRequest = DependencyRequest(collectRequest, null)
            val result = repositorySystem.resolveDependencies(session, dependencyRequest)

            val resolvedArtifact = result.artifactResults
                .firstOrNull { it.isResolved && it.artifact.groupId == groupId && it.artifact.artifactId == artifactId }
                ?.artifact?.file?.toPath()

            if (resolvedArtifact != null) {
                logger.info("Resolved $groupId:$artifactId:$version to $resolvedArtifact")
            } else {
                logger.warn("Could not resolve $groupId:$artifactId:$version")
            }
            resolvedArtifact
        } catch (e: Exception) {
            logger.error("Failed to resolve artifact $groupId:$artifactId:$version", e)
            null
        }
    }

    private fun newRepositorySystemSession(): RepositorySystemSession {
        val localRepoPath = resolveLocalRepository() ?: AetherSessionFactory.resolveLocalRepository()
        return AetherSessionFactory.createSession(localRepoPath)
    }

    override fun resolveLocalRepository(): Path? = AetherSessionFactory.resolveLocalRepository()

    @Suppress("TooGenericExceptionCaught") // Catch-all for POM parsing errors
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

    // Made public to share repository configuration with artifact resolution
    fun getRemoteRepositories(model: Model): List<RemoteRepository> {
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

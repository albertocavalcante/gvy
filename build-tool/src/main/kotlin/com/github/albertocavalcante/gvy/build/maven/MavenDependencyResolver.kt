// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.maven

import com.github.albertocavalcante.gvy.build.DependencyResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.maven.model.Model
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingRequest
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.ArtifactProperties
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.graph.Exclusion
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.DependencyResolutionException
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
    private val logger = KotlinLogging.logger {}

    override val name: String = "Maven Resolver"

    /**
     * Resolves all dependencies from a Maven project, including test scope.
     *
     * @param projectFile Path to the pom.xml file
     * @return List of resolved dependency JAR paths
     */
    override fun resolveDependencies(projectFile: Path): List<Path> = resolveDetailed(projectFile).dependencies

    /**
     * Like [resolveDependencies] but also reports artifacts that could not be resolved, so callers can
     * distinguish a complete classpath from a partial one.
     */
    fun resolveDetailed(projectFile: Path): MavenResolutionResult {
        logger.info { "Resolving dependencies using Maven Resolver for: $projectFile" }

        if (!Files.exists(projectFile)) {
            logger.error { "pom.xml not found at: $projectFile" }
            return MavenResolutionResult.EMPTY
        }

        @Suppress("TooGenericExceptionCaught") // Catch-all for resolution errors
        return try {
            val repositorySystem = AetherSessionFactory.repositorySystem
            val session = newRepositorySystemSession()

            // Parse the POM to get dependencies
            val model = parsePom(projectFile)
            if (model == null) {
                logger.error { "Failed to parse pom.xml" }
                return MavenResolutionResult.EMPTY
            }

            // Build collect request from dependencies
            val collectRequest = CollectRequest()
            collectRequest.repositories = getRemoteRepositories(model)

            model.dependencies.forEach { dep ->
                if (dep.version.isNullOrBlank()) {
                    logger.warn { "Skipping dependency with missing version: ${dep.groupId}:${dep.artifactId}" }
                    return@forEach
                }
                // Include all scopes (compile, test, etc.)
                collectRequest.addDependency(toAetherDependency(dep))
            }

            // Pin transitive versions/exclusions the way Maven does (BOMs, dependencyManagement)
            model.dependencyManagement?.dependencies
                ?.filterNot { it.version.isNullOrBlank() }
                ?.forEach { collectRequest.addManagedDependency(toAetherDependency(it, managed = true)) }

            // Resolve dependencies
            val dependencyRequest = DependencyRequest(collectRequest, null)
            val result = try {
                repositorySystem.resolveDependencies(session, dependencyRequest)
            } catch (e: DependencyResolutionException) {
                // The exception carries every artifact that did resolve; keep them.
                logger.warn(e) { "Maven Resolver could not resolve every dependency; keeping partial results" }
                return partialResult(e)
            }

            val dependencies = result.artifactResults
                .filter { it.isResolved }
                .mapNotNull { it.artifact?.file?.toPath() }

            logger.info { "Resolved ${dependencies.size} dependencies via Maven Resolver" }
            MavenResolutionResult(dependencies, emptyList())
        } catch (e: Exception) {
            logger.error(e) { "Failed to resolve dependencies with Maven Resolver" }
            MavenResolutionResult.EMPTY
        }
    }

    private fun partialResult(e: DependencyResolutionException): MavenResolutionResult {
        val artifactResults = e.result?.artifactResults.orEmpty()
        val resolved = artifactResults.filter { it.isResolved }.mapNotNull { it.artifact?.file?.toPath() }
        val unresolved = artifactResults.filterNot { it.isResolved }
            .map { it.request?.artifact?.toString() ?: "unknown artifact" } +
            e.result?.collectExceptions.orEmpty().map { "collection failed: ${it.message}" }
        val reported = unresolved.ifEmpty { listOf(e.message ?: "dependency resolution failed") }
        logger.info { "Maven Resolver kept ${resolved.size} dependencies; ${reported.size} unresolved" }
        return MavenResolutionResult(resolved, reported)
    }

    private fun toAetherDependency(dep: org.apache.maven.model.Dependency, managed: Boolean = false): Dependency {
        var artifact: Artifact = DefaultArtifact(
            dep.groupId,
            dep.artifactId,
            dep.classifier ?: "",
            dep.type ?: "jar",
            dep.version,
        )
        if (dep.scope == "system" && !dep.systemPath.isNullOrBlank()) {
            artifact = artifact.setProperties(mapOf(ArtifactProperties.LOCAL_PATH to dep.systemPath))
        }
        val exclusions = dep.exclusions.map {
            Exclusion(it.groupId, it.artifactId, "*", "*")
        }
        // Managed entries must not force defaults: an unset scope/optional means "not managed" (as in Maven).
        return if (managed) {
            Dependency(artifact, dep.scope.orEmpty(), dep.optional?.toBoolean(), exclusions)
        } else {
            Dependency(artifact, dep.scope ?: "compile", dep.isOptional, exclusions)
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
        logger.info { "Resolving artifact: $groupId:$artifactId:$version" }

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
                logger.info { "Resolved $groupId:$artifactId:$version to $resolvedArtifact" }
            } else {
                logger.warn { "Could not resolve $groupId:$artifactId:$version" }
            }
            resolvedArtifact
        } catch (e: Exception) {
            logger.error(e) { "Failed to resolve artifact $groupId:$artifactId:$version" }
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
        logger.error { "Failed to parse POM: ${e.message}" }
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

/** Outcome of an embedded Maven resolution; [unresolved] is non-empty when the classpath is partial. */
data class MavenResolutionResult(val dependencies: List<Path>, val unresolved: List<String>) {
    val isPartial: Boolean get() = unresolved.isNotEmpty()

    companion object {
        val EMPTY = MavenResolutionResult(emptyList(), emptyList())
    }
}

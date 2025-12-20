package com.github.albertocavalcante.groovylsp.buildtool.maven

import org.apache.maven.model.Model
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingRequest
import org.eclipse.aether.repository.RemoteRepository
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Interface for providing remote repository configurations.
 *
 * Abstracts the source of repository configurations to support:
 * - Maven pom.xml repositories
 * - Gradle repositories{} block
 * - settings.xml mirrors and servers
 * - Default repositories (Maven Central, Jenkins)
 *
 * This allows the SourceArtifactResolver to use project-configured
 * repositories (including Nexus/Artifactory) instead of hardcoded defaults.
 */
interface RepositoryProvider {
    /**
     * Get the list of remote repositories.
     */
    fun getRepositories(): List<RemoteRepository>
}

/**
 * Provides repositories from a Maven pom.xml file.
 *
 * Parses the POM to extract declared repositories and combines
 * them with Maven Central as the default.
 */
class MavenRepositoryProvider(private val pomPath: Path) : RepositoryProvider {

    private val logger = LoggerFactory.getLogger(MavenRepositoryProvider::class.java)

    override fun getRepositories(): List<RemoteRepository> {
        if (!Files.exists(pomPath)) {
            logger.debug("POM not found at {}, using defaults", pomPath)
            return AetherSessionFactory.getDefaultRemoteRepositories()
        }

        val model = parsePom(pomPath)
        if (model == null) {
            logger.warn("Failed to parse POM at {}, using defaults", pomPath)
            return AetherSessionFactory.getDefaultRemoteRepositories()
        }

        return buildRepositoryList(model)
    }

    private fun buildRepositoryList(model: Model): List<RemoteRepository> {
        val repos = mutableListOf<RemoteRepository>()

        // Always add Maven Central
        repos.add(
            RemoteRepository.Builder("central", "default", AetherSessionFactory.MAVEN_CENTRAL_URL).build(),
        )

        // Add repositories from POM
        model.repositories.forEach { repo ->
            logger.debug("Found repository in pom.xml: {} -> {}", repo.id, repo.url)
            repos.add(
                RemoteRepository.Builder(repo.id, "default", repo.url).build(),
            )
        }

        // Add plugin repositories (some Jenkins plugins are in plugin repos)
        model.pluginRepositories.forEach { repo ->
            // Avoid duplicates
            if (repos.none { it.id == repo.id }) {
                logger.debug("Found plugin repository in pom.xml: {} -> {}", repo.id, repo.url)
                repos.add(
                    RemoteRepository.Builder(repo.id, "default", repo.url).build(),
                )
            }
        }

        // Add Jenkins repo if not already present
        if (repos.none { it.url.contains("jenkins") }) {
            repos.add(
                RemoteRepository.Builder("jenkins", "default", AetherSessionFactory.JENKINS_REPO_URL).build(),
            )
        }

        logger.debug("Built repository list with {} entries", repos.size)
        return repos
    }

    @Suppress("TooGenericExceptionCaught") // Catch-all for POM parsing errors
    private fun parsePom(path: Path): Model? = try {
        val factory = DefaultModelBuilderFactory()
        val builder = factory.newInstance()

        val request = DefaultModelBuildingRequest()
        request.pomFile = path.toFile()
        request.validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
        request.isProcessPlugins = false
        request.isTwoPhaseBuilding = false
        request.systemProperties = System.getProperties()

        val result = builder.build(request)
        result.effectiveModel
    } catch (e: Exception) {
        logger.error("Failed to parse POM at {}: {}", path, e.message)
        null
    }
}

/**
 * Provides default repositories without any project context.
 */
class DefaultRepositoryProvider : RepositoryProvider {
    override fun getRepositories(): List<RemoteRepository> = AetherSessionFactory.getDefaultRemoteRepositories()
}

/**
 * Combines repositories from multiple providers.
 *
 * Deduplicates by repository ID, preferring earlier providers.
 */
class CompositeRepositoryProvider(private vararg val providers: RepositoryProvider) : RepositoryProvider {

    override fun getRepositories(): List<RemoteRepository> {
        val seen = mutableSetOf<String>()
        val repos = mutableListOf<RemoteRepository>()

        providers.forEach { provider ->
            provider.getRepositories().forEach { repo ->
                if (repo.id !in seen) {
                    seen.add(repo.id)
                    repos.add(repo)
                }
            }
        }

        return repos
    }
}

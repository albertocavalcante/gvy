package com.github.albertocavalcante.groovylsp.buildtool.maven

import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.impl.DefaultServiceLocator
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Factory for creating Aether (Maven Resolver) sessions.
 *
 * Consolidates Aether setup to eliminate duplication between:
 * - MavenDependencyResolver (dependency resolution)
 * - MavenSourceArtifactResolver (source JAR downloads)
 *
 * Thread-safe and lazily initializes the RepositorySystem.
 */
object AetherSessionFactory {

    private val logger = LoggerFactory.getLogger(AetherSessionFactory::class.java)

    // Lazy initialization - thread-safe singleton
    val repositorySystem: RepositorySystem by lazy { createRepositorySystem() }

    /**
     * Creates a new Aether session for the given local repository.
     *
     * @param localRepoPath Path to local Maven repository
     * @return Configured RepositorySystemSession
     */
    fun createSession(localRepoPath: Path): RepositorySystemSession {
        val session = MavenRepositorySystemUtils.newSession()
        val localRepo = LocalRepository(localRepoPath.toFile())
        session.localRepositoryManager = repositorySystem.newLocalRepositoryManager(session, localRepo)
        return session
    }

    /**
     * Resolves the local Maven repository path.
     *
     * Resolution order:
     * 1. M2_REPO environment variable
     * 2. localRepository from ~/.m2/settings.xml
     * 3. Default: ~/.m2/repository
     *
     * @return Path to local Maven repository
     */
    fun resolveLocalRepository(): Path {
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

    /**
     * Gets the default cache directory for groovy-lsp.
     *
     * @param subDir Optional subdirectory within the cache
     * @return Cache directory path
     */
    fun getCacheDirectory(subDir: String? = null): Path {
        val userHome = System.getProperty("user.home")
        val cacheDir = Paths.get(userHome, ".groovy-lsp", "cache")
        Files.createDirectories(cacheDir)
        return if (subDir != null) cacheDir.resolve(subDir) else cacheDir
    }

    /**
     * Creates default remote repositories (Maven Central + Jenkins).
     *
     * @return List of default remote repositories
     */
    fun getDefaultRemoteRepositories(): List<RemoteRepository> = listOf(
        RemoteRepository.Builder("central", "default", MAVEN_CENTRAL_URL).build(),
        RemoteRepository.Builder("jenkins", "default", JENKINS_REPO_URL).build(),
    )

    private fun createRepositorySystem(): RepositorySystem {
        val locator = MavenRepositorySystemUtils.newServiceLocator()
        locator.addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
        locator.addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)

        locator.setErrorHandler(object : DefaultServiceLocator.ErrorHandler() {
            override fun serviceCreationFailed(type: Class<*>?, impl: Class<*>?, exception: Throwable?) {
                logger.error("Aether service creation failed for {} with impl {}", type, impl, exception)
            }
        })

        return locator.getService(RepositorySystem::class.java)
    }

    // Standard Maven repository URLs
    const val MAVEN_CENTRAL_URL = "https://repo.maven.apache.org/maven2/"
    const val JENKINS_REPO_URL = "https://repo.jenkins-ci.org/public/"
}

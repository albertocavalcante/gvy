package com.github.groovylsp.bsp.maven.deps

import ch.epfl.scala.bsp4j.DependencyModule
import ch.epfl.scala.bsp4j.DependencyModulesItem
import ch.epfl.scala.bsp4j.DependencyModulesResult
import ch.epfl.scala.bsp4j.DependencySourcesItem
import ch.epfl.scala.bsp4j.DependencySourcesResult
import ch.epfl.scala.bsp4j.MavenDependencyModule
import ch.epfl.scala.bsp4j.MavenDependencyModuleArtifact
import com.github.groovylsp.bsp.maven.targets.MavenBuildTargetProvider
import com.github.groovylsp.bsp.maven.workspace.MavenModuleInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.DependencyRequest
import java.nio.file.Path

/**
 * Resolves Maven dependencies to classpath entries for BSP.
 */
class MavenDependencyProvider(
    private val repositorySystem: RepositorySystem,
    private val sessionSupplier: () -> RepositorySystemSession,
) {
    private val logger = KotlinLogging.logger {}
    private val targetProvider = MavenBuildTargetProvider()

    companion object {
        private val CENTRAL_REPO = RemoteRepository.Builder(
            "central",
            "default",
            "https://repo.maven.apache.org/maven2/",
        ).build()
    }

    /**
     * Gets dependency modules for a list of modules.
     */
    fun getDependencyModules(modules: List<MavenModuleInfo>): DependencyModulesResult {
        val items = modules.flatMap { module ->
            listOf(
                getDependencyModulesForTarget(module, MavenBuildTargetProvider.Scope.MAIN),
                getDependencyModulesForTarget(module, MavenBuildTargetProvider.Scope.TEST),
            )
        }
        return DependencyModulesResult(items)
    }

    /**
     * Gets dependency sources for a list of modules.
     */
    fun getDependencySources(modules: List<MavenModuleInfo>): DependencySourcesResult {
        val items = modules.flatMap { module ->
            listOf(
                getDependencySourcesForTarget(module, MavenBuildTargetProvider.Scope.MAIN),
                getDependencySourcesForTarget(module, MavenBuildTargetProvider.Scope.TEST),
            )
        }
        return DependencySourcesResult(items)
    }

    private fun getDependencyModulesForTarget(
        module: MavenModuleInfo,
        scope: MavenBuildTargetProvider.Scope,
    ): DependencyModulesItem {
        val targetId = targetProvider.buildTargetId(module, scope)
        val resolvedDeps = resolveDependencies(module, scope)

        val depModules = resolvedDeps.map { (artifact, path) ->
            val mavenArtifact = MavenDependencyModuleArtifact(path.toUri().toString())

            val mavenModule = MavenDependencyModule(
                artifact.groupId,
                artifact.artifactId,
                artifact.version,
                listOf(mavenArtifact),
            )

            DependencyModule(
                "${artifact.groupId}:${artifact.artifactId}",
                artifact.version,
            ).apply {
                dataKind = "maven"
                data = mavenModule
            }
        }

        return DependencyModulesItem(targetId, depModules)
    }

    private fun getDependencySourcesForTarget(
        module: MavenModuleInfo,
        scope: MavenBuildTargetProvider.Scope,
    ): DependencySourcesItem {
        val targetId = targetProvider.buildTargetId(module, scope)
        val resolvedDeps = resolveDependencies(module, scope)

        val sourcePaths = resolvedDeps.map { (_, path) -> path.toUri().toString() }

        return DependencySourcesItem(targetId, sourcePaths)
    }

    private fun resolveDependencies(
        module: MavenModuleInfo,
        scope: MavenBuildTargetProvider.Scope,
    ): List<Pair<DefaultArtifact, Path>> {
        val applicableScopes = when (scope) {
            MavenBuildTargetProvider.Scope.MAIN -> setOf("compile", "provided", "runtime")
            MavenBuildTargetProvider.Scope.TEST -> setOf("compile", "provided", "runtime", "test")
        }

        val deps = module.dependencies.filter { it.scope in applicableScopes }
        if (deps.isEmpty()) return emptyList()

        @Suppress("TooGenericExceptionCaught") // Maven resolution can fail in various ways
        return try {
            val session = sessionSupplier()
            val collectRequest = CollectRequest().apply {
                repositories = listOf(CENTRAL_REPO)
            }

            deps.forEach { dep ->
                if (dep.version.isNullOrBlank()) {
                    logger.warn { "Skipping dependency with missing version: ${dep.groupId}:${dep.artifactId}" }
                    return@forEach
                }
                val artifact = DefaultArtifact(
                    dep.groupId,
                    dep.artifactId,
                    dep.classifier ?: "",
                    dep.type,
                    dep.version,
                )
                collectRequest.addDependency(Dependency(artifact, dep.scope))
            }

            val dependencyRequest = DependencyRequest(collectRequest, null)
            val result = repositorySystem.resolveDependencies(session, dependencyRequest)

            result.artifactResults
                .filter { it.isResolved }
                .mapNotNull { artifactResult ->
                    val artifact = artifactResult.artifact
                    val file = artifact?.file
                    if (artifact != null && file != null) {
                        DefaultArtifact(
                            artifact.groupId,
                            artifact.artifactId,
                            artifact.classifier,
                            artifact.extension,
                            artifact.version,
                        ) to file.toPath()
                    } else {
                        null
                    }
                }
        } catch (e: org.eclipse.aether.resolution.DependencyResolutionException) {
            logger.warn { "Failed to resolve dependencies for ${module.moduleId}: ${e.message}" }
            emptyList()
        } catch (e: Exception) {
            logger.warn { "Unexpected error resolving dependencies for ${module.moduleId}: ${e.message}" }
            emptyList()
        }
    }
}

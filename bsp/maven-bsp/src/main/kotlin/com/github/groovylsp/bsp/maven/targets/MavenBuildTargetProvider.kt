package com.github.groovylsp.bsp.maven.targets

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetCapabilities
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.BuildTargetTag
import com.github.groovylsp.bsp.maven.workspace.MavenModuleInfo

/**
 * Creates BSP BuildTarget objects from Maven module information.
 */
class MavenBuildTargetProvider {

    companion object {
        private val SUPPORTED_LANGUAGES = listOf("java", "groovy", "kotlin")
        private val COMPILABLE_PACKAGING = setOf("jar", "war", "ear", "hpi", "bundle")
    }

    /**
     * Creates BSP build targets from Maven modules.
     * Each compilable module produces a main target and a test target.
     */
    fun createTargets(modules: List<MavenModuleInfo>): List<BuildTarget> {
        val moduleMap = modules.associateBy { "${it.groupId}:${it.artifactId}" }

        return modules.flatMap { module ->
            if (shouldCreateTargets(module)) {
                listOf(
                    createMainTarget(module, moduleMap),
                    createTestTarget(module, moduleMap),
                )
            } else {
                emptyList()
            }
        }
    }

    /**
     * Creates a build target identifier for a Maven module.
     */
    fun buildTargetId(module: MavenModuleInfo, scope: Scope): BuildTargetIdentifier {
        val uri = when (scope) {
            Scope.MAIN -> "maven:${module.groupId}:${module.artifactId}"
            Scope.TEST -> "maven:${module.groupId}:${module.artifactId}:test"
        }
        return BuildTargetIdentifier(uri)
    }

    private fun shouldCreateTargets(module: MavenModuleInfo): Boolean {
        // Skip aggregator-only pom modules (packaging=pom with child modules)
        if (module.packaging == "pom" && module.modules.isNotEmpty()) {
            return false
        }
        // Include jar, war, ear, hpi, and other compilable types
        return module.packaging in COMPILABLE_PACKAGING || module.packaging == "pom"
    }

    private fun createMainTarget(module: MavenModuleInfo, moduleMap: Map<String, MavenModuleInfo>): BuildTarget {
        val targetId = buildTargetId(module, Scope.MAIN)

        // Find inter-module dependencies
        val dependencies = module.dependencies
            .filter { it.scope == "compile" || it.scope == "provided" || it.scope == "runtime" }
            .filter { dep -> "${dep.groupId}:${dep.artifactId}" in moduleMap }
            .map { dep ->
                val depModule = moduleMap["${dep.groupId}:${dep.artifactId}"]!!
                buildTargetId(depModule, Scope.MAIN)
            }

        return BuildTarget(
            targetId,
            listOf(BuildTargetTag.LIBRARY),
            SUPPORTED_LANGUAGES,
            dependencies,
            BuildTargetCapabilities(),
        ).apply {
            displayName = module.artifactId
            baseDirectory = module.baseDir.toUri().toString()
            capabilities.canCompile = true
            capabilities.canTest = false
            capabilities.canRun = true
            capabilities.canDebug = true
        }
    }

    private fun createTestTarget(module: MavenModuleInfo, moduleMap: Map<String, MavenModuleInfo>): BuildTarget {
        val targetId = buildTargetId(module, Scope.TEST)
        val mainTargetId = buildTargetId(module, Scope.MAIN)

        // Test target depends on main target + test-scoped inter-module deps
        val dependencies = mutableListOf(mainTargetId)

        module.dependencies
            .filter { it.scope == "test" }
            .filter { dep -> "${dep.groupId}:${dep.artifactId}" in moduleMap }
            .forEach { dep ->
                val depModule = moduleMap["${dep.groupId}:${dep.artifactId}"]!!
                dependencies.add(buildTargetId(depModule, Scope.MAIN))
            }

        return BuildTarget(
            targetId,
            listOf(BuildTargetTag.TEST),
            SUPPORTED_LANGUAGES,
            dependencies,
            BuildTargetCapabilities(),
        ).apply {
            displayName = "${module.artifactId} (test)"
            baseDirectory = module.baseDir.toUri().toString()
            capabilities.canCompile = true
            capabilities.canTest = true
            capabilities.canRun = true
            capabilities.canDebug = true
        }
    }

    enum class Scope {
        MAIN,
        TEST,
    }
}

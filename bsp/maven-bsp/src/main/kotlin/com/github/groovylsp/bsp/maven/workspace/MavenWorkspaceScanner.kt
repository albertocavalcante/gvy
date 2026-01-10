package com.github.groovylsp.bsp.maven.workspace

import org.apache.maven.model.Model
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingRequest
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Scans a Maven workspace and discovers all modules.
 *
 * This scanner:
 * - Detects single-module and multi-module Maven projects
 * - Parses pom.xml files using Maven Embedder
 * - Resolves parent-child inheritance
 * - Handles property interpolation
 */
class MavenWorkspaceScanner {
    private val logger = LoggerFactory.getLogger(MavenWorkspaceScanner::class.java)

    /**
     * Scans a workspace root directory for Maven modules.
     *
     * @param workspaceRoot The root directory to scan (must contain pom.xml)
     * @return List of all discovered Maven modules, or empty if not a Maven project
     */
    fun scan(workspaceRoot: Path): List<MavenModuleInfo> {
        val rootPom = workspaceRoot.resolve("pom.xml")
        if (!rootPom.exists()) {
            logger.debug("No pom.xml found at workspace root: $workspaceRoot")
            return emptyList()
        }

        val modules = mutableListOf<MavenModuleInfo>()
        scanRecursive(rootPom, modules)
        return modules
    }

    /**
     * Parses a single pom.xml file into module info.
     *
     * @param pomPath Path to the pom.xml file
     * @return Parsed module info, or null if parsing fails
     */
    fun parseModule(pomPath: Path): MavenModuleInfo? {
        if (!pomPath.exists()) {
            logger.debug("POM file does not exist: $pomPath")
            return null
        }

        val model = parsePom(pomPath) ?: return null
        return modelToModuleInfo(model, pomPath)
    }

    private fun scanRecursive(pomPath: Path, modules: MutableList<MavenModuleInfo>) {
        val module = parseModule(pomPath) ?: return
        modules.add(module)

        // Scan child modules
        for (childModuleName in module.modules) {
            val childDir = pomPath.parent.resolve(childModuleName)
            val childPom = childDir.resolve("pom.xml")

            if (childDir.exists() && childDir.isDirectory() && childPom.exists()) {
                scanRecursive(childPom, modules)
            } else {
                logger.debug("Skipping non-Maven module: $childModuleName at $childDir")
            }
        }
    }

    private fun parsePom(pomPath: Path): Model? = try {
        val factory = DefaultModelBuilderFactory()
        val builder = factory.newInstance()

        val request = DefaultModelBuildingRequest().apply {
            pomFile = pomPath.toFile()
            validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
            isProcessPlugins = true
            isTwoPhaseBuilding = false
            systemProperties = System.getProperties()
        }

        val result = builder.build(request)
        result.effectiveModel
    } catch (e: org.apache.maven.model.building.ModelBuildingException) {
        logger.debug("Failed to parse POM at $pomPath: ${e.message}")
        null
    }

    private fun modelToModuleInfo(model: Model, pomPath: Path): MavenModuleInfo {
        // Extract dependencies
        val dependencies = model.dependencies.map { dep ->
            MavenDependency(
                groupId = dep.groupId ?: "",
                artifactId = dep.artifactId ?: "",
                version = dep.version,
                scope = dep.scope ?: "compile",
                type = dep.type ?: "jar",
                classifier = dep.classifier,
                optional = dep.isOptional,
            )
        }

        // Extract parent info
        val parentInfo = model.parent?.let { parent ->
            ParentInfo(
                groupId = parent.groupId ?: "",
                artifactId = parent.artifactId ?: "",
                version = parent.version ?: "",
                relativePath = parent.relativePath,
            )
        }

        // Extract source directories (from build section)
        val sourceDirectory = model.build?.sourceDirectory?.let { source ->
            // Convert absolute to relative if possible
            val pomDir = pomPath.parent.toAbsolutePath()
            val sourcePath = Path.of(source)
            if (sourcePath.isAbsolute && sourcePath.startsWith(pomDir)) {
                pomDir.relativize(sourcePath).toString()
            } else {
                source
            }
        }

        val testSourceDirectory = model.build?.testSourceDirectory?.let { source ->
            val pomDir = pomPath.parent.toAbsolutePath()
            val sourcePath = Path.of(source)
            if (sourcePath.isAbsolute && sourcePath.startsWith(pomDir)) {
                pomDir.relativize(sourcePath).toString()
            } else {
                source
            }
        }

        return MavenModuleInfo(
            pomPath = pomPath,
            groupId = model.groupId ?: model.parent?.groupId ?: "",
            artifactId = model.artifactId ?: "",
            version = model.version ?: model.parent?.version ?: "",
            packaging = model.packaging ?: "jar",
            modules = model.modules ?: emptyList(),
            dependencies = dependencies,
            parent = parentInfo,
            sourceDirectory = sourceDirectory,
            testSourceDirectory = testSourceDirectory,
        )
    }
}

package com.github.albertocavalcante.groovylsp.buildtool.jdk

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.maven.model.Model
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingRequest
import org.codehaus.plexus.util.xml.Xpp3Dom
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists

/**
 * Extracts JDK version requirements from Maven pom.xml and toolchains.xml files.
 *
 * Checks (in priority order):
 * 1. maven-compiler-plugin release parameter (JDK 9+)
 * 2. maven-compiler-plugin source/target parameters
 * 3. maven.compiler.source/target/release properties
 * 4. Maven toolchains.xml (if referenced in pom.xml)
 */
class MavenJdkRequirementExtractor : JdkRequirementExtractor {
    private val logger = KotlinLogging.logger {}

    override fun extract(workspaceRoot: Path): JdkRequirementResult {
        val pomPath = workspaceRoot.resolve("pom.xml")
        if (!pomPath.exists()) {
            return JdkRequirementResult.NotConfigured("Maven")
        }

        return runCatching {
            val model = parsePom(pomPath)
            if (model == null) {
                return JdkRequirementResult.ParseError("Failed to parse pom.xml")
            }
            extractFromModel(model, workspaceRoot)
        }.getOrElse { e ->
            if (e is Error) throw e
            logger.warn(e) { "Failed to extract JDK requirements from pom.xml" }
            JdkRequirementResult.ParseError("Error parsing pom.xml: ${e.message}", e)
        }
    }

    private fun extractFromModel(model: Model, workspaceRoot: Path): JdkRequirementResult {
        // 1. Check maven-compiler-plugin configuration
        val compilerConfig = findCompilerPluginConfig(model)

        // 2. Check properties
        val properties = model.properties
        val releaseProperty = properties?.getProperty("maven.compiler.release")
        val sourceProperty = properties?.getProperty("maven.compiler.source")
        val targetProperty = properties?.getProperty("maven.compiler.target")

        // 3. Determine effective versions and track their sources
        // Plugin config takes precedence over properties
        val releaseFromPlugin = compilerConfig?.release?.let { parseJavaVersion(it) }
        val releaseFromProperty = releaseProperty?.let { parseJavaVersion(it) }
        val releaseVersion = releaseFromPlugin ?: releaseFromProperty

        val sourceFromPlugin = compilerConfig?.source?.let { parseJavaVersion(it) }
        val sourceFromProperty = sourceProperty?.let { parseJavaVersion(it) }
        val sourceVersion = sourceFromPlugin ?: sourceFromProperty

        val targetFromPlugin = compilerConfig?.target?.let { parseJavaVersion(it) }
        val targetFromProperty = targetProperty?.let { parseJavaVersion(it) }
        val targetVersion = targetFromPlugin ?: targetFromProperty

        // 4. Check for toolchain usage
        val toolchainVersion = if (hasToolchainPlugin(model)) {
            extractToolchainVersion(workspaceRoot)
        } else {
            null
        }

        // If no configuration found, project uses default (JDK running Maven)
        if (releaseVersion == null && sourceVersion == null && targetVersion == null && toolchainVersion == null) {
            return JdkRequirementResult.NotConfigured("Maven")
        }

        // Determine the source of the requirement based on WHERE the values actually came from
        val source = when {
            toolchainVersion != null -> RequirementSource.MAVEN_TOOLCHAIN
            releaseFromPlugin != null -> RequirementSource.MAVEN_RELEASE_PROPERTY
            releaseFromProperty != null -> RequirementSource.MAVEN_RELEASE_PROPERTY
            sourceFromPlugin != null || targetFromPlugin != null -> RequirementSource.MAVEN_COMPILER_PLUGIN
            else -> RequirementSource.MAVEN_SOURCE_TARGET_PROPERTY
        }

        return JdkRequirementResult.Found(
            JdkRequirement(
                sourceVersion = sourceVersion,
                targetVersion = releaseVersion ?: targetVersion,
                toolchainVersion = toolchainVersion,
                source = source,
            ),
        )
    }

    private data class CompilerConfig(
        val source: String? = null,
        val target: String? = null,
        val release: String? = null,
    )

    private fun findCompilerPluginConfig(model: Model): CompilerConfig? {
        val plugin = model.build?.plugins?.find {
            (it.groupId == "org.apache.maven.plugins" || it.groupId == null) &&
                it.artifactId == "maven-compiler-plugin"
        } ?: model.build?.pluginManagement?.plugins?.find {
            (it.groupId == "org.apache.maven.plugins" || it.groupId == null) &&
                it.artifactId == "maven-compiler-plugin"
        }

        if (plugin == null) return null

        val config = plugin.configuration as? Xpp3Dom ?: return null

        return CompilerConfig(
            source = config.getChild("source")?.value,
            target = config.getChild("target")?.value,
            release = config.getChild("release")?.value,
        )
    }

    private fun hasToolchainPlugin(model: Model): Boolean {
        val plugins = (model.build?.plugins ?: emptyList()) +
            (model.build?.pluginManagement?.plugins ?: emptyList())

        return plugins.any {
            (it.groupId == "org.apache.maven.plugins" || it.groupId == null) &&
                it.artifactId == "maven-toolchains-plugin"
        }
    }

    private fun extractToolchainVersion(workspaceRoot: Path): Int? {
        // Check for toolchains.xml in standard locations
        val userHome = System.getProperty("user.home") ?: return null
        val toolchainsPaths = listOf(
            workspaceRoot.resolve(".mvn/toolchains.xml"),
            Path.of(userHome, ".m2", "toolchains.xml"),
        )

        val toolchainsFile = toolchainsPaths.firstOrNull { it.exists() } ?: return null

        return runCatching {
            parseToolchainsXml(toolchainsFile)
        }.onFailure { e ->
            if (e is Error) throw e
            logger.debug(e) { "Failed to parse toolchains.xml" }
        }.getOrNull()
    }

    private fun parseToolchainsXml(toolchainsPath: Path): Int? {
        val factory = DocumentBuilderFactory.newInstance()
        // Secure XML parsing - prevent XXE attacks
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false

        val builder = factory.newDocumentBuilder()
        val document = builder.parse(toolchainsPath.toFile())

        val toolchains = document.getElementsByTagName("toolchain")
        for (i in 0 until toolchains.length) {
            val toolchain = toolchains.item(i)
            val children = toolchain.childNodes

            var type: String? = null
            var version: String? = null

            for (j in 0 until children.length) {
                val child = children.item(j)
                when (child.nodeName) {
                    "type" -> type = child.textContent?.trim()
                    "provides" -> {
                        val provides = child.childNodes
                        for (k in 0 until provides.length) {
                            val provide = provides.item(k)
                            if (provide.nodeName == "version") {
                                version = provide.textContent?.trim()
                            }
                        }
                    }
                }
            }

            // Only consider JDK toolchains
            if (type == "jdk" && version != null) {
                parseJavaVersion(version)?.let { return it }
            }
        }

        return null
    }

    private fun parsePom(pomPath: Path): Model? {
        val factory = DefaultModelBuilderFactory()
        val builder = factory.newInstance()

        val request = DefaultModelBuildingRequest().apply {
            pomFile = pomPath.toFile()
            validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
            isProcessPlugins = true
            isTwoPhaseBuilding = false
            systemProperties = System.getProperties()
        }

        return runCatching { builder.build(request).effectiveModel }
            .onFailure { e ->
                if (e is Error) throw e
                logger.debug(e) { "Failed to build Maven model" }
            }
            .getOrNull()
    }

    /**
     * Parses Java version strings like "1.8", "11", "17", "21" to major version int.
     */
    private fun parseJavaVersion(version: String): Int? {
        val trimmed = version.trim()
        return when {
            trimmed.startsWith("1.") -> trimmed.removePrefix("1.").substringBefore(".").toIntOrNull()
            else -> trimmed.substringBefore(".").substringBefore("-").toIntOrNull()
        }
    }
}

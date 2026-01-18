package com.github.albertocavalcante.groovylsp.buildtool.jdk

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.maven.model.Model
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingRequest
import org.codehaus.plexus.util.xml.Xpp3Dom
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
        val compilerConfig = findCompilerPluginConfig(model)
        val versions = extractVersions(model, compilerConfig)
        val toolchainVersion = extractToolchainVersionIfConfigured(model, workspaceRoot)

        if (hasNoJdkConfiguration(versions, toolchainVersion)) {
            return JdkRequirementResult.NotConfigured("Maven")
        }

        val source = determineRequirementSource(versions, toolchainVersion)

        return JdkRequirementResult.Found(
            JdkRequirement(
                sourceVersion = versions.sourceVersion,
                targetVersion = versions.releaseVersion ?: versions.targetVersion,
                toolchainVersion = toolchainVersion,
                source = source,
            ),
        )
    }

    private data class ExtractedVersions(
        val releaseVersion: Int?,
        val sourceVersion: Int?,
        val targetVersion: Int?,
        val releaseFromPlugin: Int?,
        val releaseFromProperty: Int?,
        val sourceFromPlugin: Int?,
        val targetFromPlugin: Int?,
    )

    private fun extractVersions(model: Model, compilerConfig: CompilerConfig?): ExtractedVersions {
        val properties = model.properties
        val releaseProperty = properties?.getProperty("maven.compiler.release")
        val sourceProperty = properties?.getProperty("maven.compiler.source")
        val targetProperty = properties?.getProperty("maven.compiler.target")

        val releaseFromPlugin = compilerConfig?.release?.let { parseJavaVersion(it) }
        val releaseFromProperty = releaseProperty?.let { parseJavaVersion(it) }
        val releaseVersion = releaseFromPlugin ?: releaseFromProperty

        val sourceFromPlugin = compilerConfig?.source?.let { parseJavaVersion(it) }
        val sourceFromProperty = sourceProperty?.let { parseJavaVersion(it) }
        val sourceVersion = sourceFromPlugin ?: sourceFromProperty

        val targetFromPlugin = compilerConfig?.target?.let { parseJavaVersion(it) }
        val targetFromProperty = targetProperty?.let { parseJavaVersion(it) }
        val targetVersion = targetFromPlugin ?: targetFromProperty

        return ExtractedVersions(
            releaseVersion = releaseVersion,
            sourceVersion = sourceVersion,
            targetVersion = targetVersion,
            releaseFromPlugin = releaseFromPlugin,
            releaseFromProperty = releaseFromProperty,
            sourceFromPlugin = sourceFromPlugin,
            targetFromPlugin = targetFromPlugin,
        )
    }

    private fun extractToolchainVersionIfConfigured(model: Model, workspaceRoot: Path): Int? =
        if (hasToolchainPlugin(model)) extractToolchainVersion(workspaceRoot) else null

    private fun hasNoJdkConfiguration(versions: ExtractedVersions, toolchainVersion: Int?): Boolean =
        versions.releaseVersion == null && versions.sourceVersion == null &&
            versions.targetVersion == null && toolchainVersion == null

    private fun determineRequirementSource(versions: ExtractedVersions, toolchainVersion: Int?): RequirementSource =
        when {
            toolchainVersion != null -> RequirementSource.MAVEN_TOOLCHAIN
            versions.releaseVersion != null -> RequirementSource.MAVEN_RELEASE_PROPERTY
            versions.sourceFromPlugin != null || versions.targetFromPlugin != null ->
                RequirementSource.MAVEN_COMPILER_PLUGIN
            else -> RequirementSource.MAVEN_SOURCE_TARGET_PROPERTY
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
        val document = parseToolchainsDocument(toolchainsPath)
        val toolchains = document.getElementsByTagName("toolchain")

        for (i in 0 until toolchains.length) {
            val toolchain = toolchains.item(i)
            val jdkVersion = extractJdkVersionFromToolchain(toolchain)
            if (jdkVersion != null) {
                return jdkVersion
            }
        }

        return null
    }

    private fun parseToolchainsDocument(toolchainsPath: Path): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        // Secure XML parsing - prevent XXE attacks
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false

        val builder = factory.newDocumentBuilder()
        return builder.parse(toolchainsPath.toFile())
    }

    private fun extractJdkVersionFromToolchain(toolchain: org.w3c.dom.Node): Int? {
        val children = toolchain.childNodes
        var type: String? = null
        var version: String? = null

        for (j in 0 until children.length) {
            val child = children.item(j)
            when (child.nodeName) {
                "type" -> type = child.textContent?.trim()
                "provides" -> version = extractVersionFromProvides(child)
            }
        }

        // Only consider JDK toolchains
        return if (type == "jdk" && version != null) {
            parseJavaVersion(version)
        } else {
            null
        }
    }

    private fun extractVersionFromProvides(providesNode: org.w3c.dom.Node): String? {
        val provides = providesNode.childNodes
        for (k in 0 until provides.length) {
            val provide = provides.item(k)
            if (provide.nodeName == "version") {
                return provide.textContent?.trim()
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

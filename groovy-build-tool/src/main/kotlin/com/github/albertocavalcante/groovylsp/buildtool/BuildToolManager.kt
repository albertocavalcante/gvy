package com.github.albertocavalcante.groovylsp.buildtool

import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Manages build tool detection and selection for workspaces.
 *
 * ## Design Decision: Auto-Detection Strategy
 *
 * This class deliberately uses **auto-detection** of build files (build.gradle, pom.xml, etc.)
 * rather than requiring explicit "Import Project" actions. This decision was made after researching
 * approaches used by other language servers:
 *
 * | Tool         | Approach                           |
 * |--------------|------------------------------------|
 * | rust-analyzer| Auto-detect Cargo.toml             |
 * | vscode-java  | Lightweight → Standard mode import |
 * | Metals       | BSP with explicit build connect    |
 * | IntelliJ     | Explicit "Import Project" wizard   |
 *
 * **Rationale for auto-detect:**
 * - Most Groovy/Jenkins users expect IDE-like "just works" behavior
 * - CLI commands like `gls check` require auto-resolution for non-interactive use
 * - Jenkins Pipeline workspaces (no build.gradle) still work for syntax-only mode
 * - Lower friction for the common case
 *
 * **Trade-offs:**
 * - No explicit control over when resolution happens
 * - Silent failures can be confusing (mitigated by enhanced error notifications)
 * - Can be slow on first open with large projects
 *
 * **Future consideration:** Add `groovy.build.autoResolve` setting toggle if users request it.
 *
 * @param buildTools List of available build tools, in priority order
 * @param gradleBuildStrategy Strategy for Gradle project dependency resolution
 */
class BuildToolManager(
    private val buildTools: List<BuildTool>,
    private val gradleBuildStrategy: GradleBuildStrategy = GradleBuildStrategy.AUTO,
) {
    private val logger = LoggerFactory.getLogger(BuildToolManager::class.java)

    /**
     * Detects the appropriate build tool for the given workspace.
     * The detection is influenced by [gradleBuildStrategy] for Gradle projects.
     *
     * @param workspaceRoot The root directory of the workspace
     * @return The detected build tool, or null if none found
     */
    fun detectBuildTool(workspaceRoot: Path): BuildTool? {
        logger.info("Detecting build tool for workspace: $workspaceRoot (strategy=$gradleBuildStrategy)")

        val isGradleProject = isGradleProject(workspaceRoot)
        val hasBspConnection = hasBspConnection(workspaceRoot)

        logger.debug("Gradle project: $isGradleProject, BSP available: $hasBspConnection")

        return when (gradleBuildStrategy) {
            GradleBuildStrategy.AUTO -> detectAuto(workspaceRoot)

            GradleBuildStrategy.NATIVE_ONLY -> {
                if (isGradleProject) {
                    // Skip BSP for Gradle, use native Gradle tool directly
                    logger.info("NATIVE_ONLY: Skipping BSP, looking for native Gradle tool")
                    findGradleBuildTool(workspaceRoot)
                        ?: detectAuto(workspaceRoot) // Fallback to normal detection
                } else {
                    // Non-Gradle projects: normal detection (BSP for Bazel, sbt, etc.)
                    detectAuto(workspaceRoot)
                }
            }

            GradleBuildStrategy.BSP_PREFERRED -> {
                if (isGradleProject && hasBspConnection) {
                    // Prefer BSP for Gradle when available
                    logger.info("BSP_PREFERRED: Using BSP for Gradle project")
                    findBspBuildTool(workspaceRoot)
                        ?: detectAuto(workspaceRoot)
                } else {
                    detectAuto(workspaceRoot)
                }
            }
        }
    }

    /**
     * Default AUTO detection: first matching build tool wins.
     */
    private fun detectAuto(workspaceRoot: Path): BuildTool? = buildTools.firstOrNull { tool ->
        val canHandle = tool.canHandle(workspaceRoot)
        logger.debug("Build tool '${tool.name}' canHandle=$canHandle")
        canHandle
    }

    /**
     * Finds a native Gradle build tool (not BSP) that can handle this workspace.
     */
    private fun findGradleBuildTool(workspaceRoot: Path): BuildTool? = buildTools
        .filterIsInstance<NativeGradleBuildTool>()
        .firstOrNull { it.canHandle(workspaceRoot) }

    /**
     * Finds a BSP build tool that can handle this workspace.
     */
    private fun findBspBuildTool(workspaceRoot: Path): BuildTool? = buildTools
        .filterIsInstance<BspCompatibleBuildTool>()
        .firstOrNull { it.canHandle(workspaceRoot) }

    /**
     * Checks if the workspace is a Gradle project.
     */
    private fun isGradleProject(workspaceRoot: Path): Boolean =
        com.github.albertocavalcante.groovylsp.buildtool.gradle.GradleBuildFiles.fileNames
            .any { workspaceRoot.resolve(it).exists() }

    /**
     * Checks if a BSP connection file exists.
     */
    private fun hasBspConnection(workspaceRoot: Path): Boolean {
        val bspDir = workspaceRoot.resolve(".bsp")
        if (!bspDir.exists()) return false
        return bspDir.toFile().listFiles()?.any { it.extension == "json" } ?: false
    }
}

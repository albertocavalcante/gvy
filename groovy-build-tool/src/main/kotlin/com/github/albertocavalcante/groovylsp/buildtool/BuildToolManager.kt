package com.github.albertocavalcante.groovylsp.buildtool

import org.slf4j.LoggerFactory
import java.nio.file.Path

class BuildToolManager(private val buildTools: List<BuildTool>) {
    private val logger = LoggerFactory.getLogger(BuildToolManager::class.java)

    /**
     * Detects the appropriate build tool for the given workspace.
     * Returns the first matching build tool, or null if none found.
     */
    fun detectBuildTool(workspaceRoot: Path): BuildTool? {
        logger.info("Detecting build tool for workspace: $workspaceRoot")
        return buildTools.firstOrNull { tool ->
            val canHandle = tool.canHandle(workspaceRoot)
            logger.debug("Build tool '${tool.name}' canHandle=$canHandle")
            canHandle
        }
    }
}

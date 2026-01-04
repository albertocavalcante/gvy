package com.github.albertocavalcante.groovylsp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.path
import com.github.albertocavalcante.groovylsp.buildtool.BuildToolManager
import com.github.albertocavalcante.groovylsp.buildtool.bsp.BspBuildTool
import com.github.albertocavalcante.groovylsp.buildtool.gradle.GradleBuildTool
import com.github.albertocavalcante.groovylsp.buildtool.maven.MavenBuildTool
import com.github.albertocavalcante.groovylsp.indexing.IndexFormat
import com.github.albertocavalcante.groovylsp.providers.indexing.ExportIndexParams
import com.github.albertocavalcante.groovylsp.services.IndexExportService
import java.io.File
import java.nio.file.Path

class IndexCommand : CliktCommand(name = "index") {

    override fun help(context: Context) = "Generate SCIP or LSIF index for a project"

    private val format by option("--format", help = "Output format (SCIP or LSIF)")
        .enum<IndexFormat>()

    private val output by option("--output", help = "Output file path")
        .file(mustExist = false)

    private val projectRoot by argument(help = "Project root directory")
        .path(mustExist = true, canBeFile = false)
        .default(Path.of("."))

    override fun run() {
        val rootPath = projectRoot.toAbsolutePath().normalize()
        val actualFormat = format ?: IndexFormat.SCIP
        val outputFile = output ?: File("index.${actualFormat.toString().lowercase()}")

        val params = ExportIndexParams(
            format = actualFormat,
            outputPath = outputFile.absolutePath,
        )

        val buildTools = listOf(
            BspBuildTool(),
            GradleBuildTool(),
            MavenBuildTool(),
        )
        val buildToolManager = BuildToolManager(buildTools)
        val service = IndexExportService { buildToolManager }

        val result =
            runCatching { service.exportIndex(params, rootPath) }
                .onFailure { throwable ->
                    echo("Failed to generate index: ${throwable.message}", err = true)
                    if (throwable is Error) {
                        throw throwable
                    }
                }
                .getOrThrow()

        echo(result)
    }
}

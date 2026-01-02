package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.buildtool.BuildTool
import com.github.albertocavalcante.groovylsp.buildtool.BuildToolManager
import com.github.albertocavalcante.groovylsp.indexing.IndexFormat
import com.github.albertocavalcante.groovylsp.indexing.IndexWriter
import com.github.albertocavalcante.groovylsp.indexing.UnifiedIndexer
import com.github.albertocavalcante.groovylsp.providers.indexing.ExportIndexParams
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class IndexExportServiceTest {

    @Test
    fun `should export index`() {
        val buildToolManager = mockk<BuildToolManager>()
        val buildTool = mockk<BuildTool>()
        every { buildToolManager.detectBuildTool(any()) } returns buildTool
        every { buildTool.name } returns "Gradle"

        val service = IndexExportService { buildToolManager }
        val tempDir = createTempDirectory("index-test")
        val outputFile = tempDir.resolve("index.scip").toAbsolutePath().toString()
        val params = ExportIndexParams(IndexFormat.SCIP, outputFile)

        // Create a dummy groovy file
        val groovyFile = tempDir.resolve("Test.groovy")
        Files.writeString(groovyFile, "class Test {}")

        // We can't easily assert internal behavior since IndexExportService instantiates writers/indexer internally.
        // We rely on the fact that it doesn't throw and produces a success message.
        // E2E tests cover the actual output content.

        val result = service.exportIndex(params, tempDir)

        kotlin.test.assertTrue(result.startsWith("Successfully exported SCIP index"))
        kotlin.test.assertTrue(Files.exists(java.nio.file.Paths.get(outputFile)))

        // Cleanup
        tempDir.toFile().deleteRecursively()
    }
}

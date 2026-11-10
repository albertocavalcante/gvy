package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovylsp.indexing.IndexFormat
import com.github.albertocavalcante.gvy.build.BuildTool
import com.github.albertocavalcante.gvy.build.BuildToolManager
import com.github.albertocavalcante.gvy.gls.providers.indexing.ExportIndexParams
import io.mockk.every
import io.mockk.mockk
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

    @Test
    fun `should skip hidden and build output directories`() {
        val workspace = createTempDirectory("index-skip-test")
        val outDir = createTempDirectory("index-skip-out")
        try {
            val files = mapOf(
                "src/Foo.groovy" to "class Foo {}",
                "vars/bar.groovy" to "def call() {}",
                ".git/hooks/GitHook.groovy" to "class GitHook {}",
                ".jenkins-stubs/org/jenkinsci/plugins/workflow/cps/CpsScript.groovy" to "class CpsScript {}",
                ".gradle/GradleCache.groovy" to "class GradleCache {}",
                "build/generated/Gen.groovy" to "class Gen {}",
                "out/Out.groovy" to "class Out {}",
                "target/Target.groovy" to "class Target {}",
                "node_modules/pkg/Node.groovy" to "class Node {}",
            )
            files.forEach { (rel, content) ->
                val file = workspace.resolve(rel)
                Files.createDirectories(file.parent)
                Files.writeString(file, content)
            }
            val output = outDir.resolve("index.scip")

            IndexExportService { null }.exportIndex(ExportIndexParams(IndexFormat.SCIP, output.toString()), workspace)

            // SCIP stores document paths as plain UTF-8 strings, so a Latin-1 view is enough to search them.
            val text = String(Files.readAllBytes(output), Charsets.ISO_8859_1)
            kotlin.test.assertTrue(text.contains("src/Foo.groovy"), "src/Foo.groovy must be indexed")
            kotlin.test.assertTrue(text.contains("vars/bar.groovy"), "vars/bar.groovy must be indexed")
            listOf(
                "GitHook.groovy",
                "CpsScript.groovy",
                "GradleCache.groovy",
                "Gen.groovy",
                "Out.groovy",
                "Target.groovy",
                "Node.groovy",
            ).forEach { kotlin.test.assertFalse(text.contains(it), "$it must not be indexed") }
        } finally {
            workspace.toFile().deleteRecursively()
            outDir.toFile().deleteRecursively()
        }
    }
}

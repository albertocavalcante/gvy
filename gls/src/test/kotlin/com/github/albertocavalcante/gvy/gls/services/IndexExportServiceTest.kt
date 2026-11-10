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

    private fun exportedText(workspace: java.nio.file.Path, files: Map<String, String>): String {
        val outDir = createTempDirectory("index-out")
        try {
            files.forEach { (rel, content) ->
                val file = workspace.resolve(rel)
                Files.createDirectories(file.parent)
                Files.writeString(file, content)
            }
            val output = outDir.resolve("index.scip")
            IndexExportService { null }.exportIndex(ExportIndexParams(IndexFormat.SCIP, output.toString()), workspace)
            return String(Files.readAllBytes(output), Charsets.ISO_8859_1)
        } finally {
            outDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `should index symlinked source files`() {
        val workspace = createTempDirectory("index-link-ws")
        val external = createTempDirectory("index-link-ext")
        try {
            val target = external.resolve("Linked.groovy")
            Files.writeString(target, "class Linked {}")
            Files.createDirectories(workspace.resolve("src"))
            try {
                Files.createSymbolicLink(workspace.resolve("src/Linked.groovy"), target)
            } catch (e: UnsupportedOperationException) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unsupported: $e")
            } catch (e: java.io.IOException) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unsupported: $e")
            }
            val text = exportedText(workspace, mapOf("src/Foo.groovy" to "class Foo {}"))
            kotlin.test.assertTrue(text.contains("src/Linked.groovy"), "symlinked file must be indexed")
        } finally {
            workspace.toFile().deleteRecursively()
            external.toFile().deleteRecursively()
        }
    }

    @Test
    fun `should index packages named build under source roots`() {
        val workspace = createTempDirectory("index-pkg-ws")
        try {
            val text = exportedText(
                workspace,
                mapOf(
                    "src/main/groovy/com/acme/build/PkgBuild.groovy" to "class PkgBuild {}",
                    "src/org/foo/build/JenkinsBuild.groovy" to "class JenkinsBuild {}",
                    "vars/target/VarsTarget.groovy" to "class VarsTarget {}",
                    "build/generated/TopGen.groovy" to "class TopGen {}",
                ),
            )
            kotlin.test.assertTrue(text.contains("PkgBuild.groovy"))
            kotlin.test.assertTrue(text.contains("JenkinsBuild.groovy"))
            kotlin.test.assertTrue(text.contains("VarsTarget.groovy"))
            kotlin.test.assertFalse(text.contains("TopGen.groovy"), "top-level build must be skipped")
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `should skip build output of subprojects`() {
        val workspace = createTempDirectory("index-sub-ws")
        try {
            val text = exportedText(
                workspace,
                mapOf(
                    "sub/build.gradle" to "// build",
                    "sub/src/Sub.groovy" to "class Sub {}",
                    "sub/build/generated/SubGen.groovy" to "class SubGen {}",
                    "plain/build/Kept.groovy" to "class Kept {}",
                ),
            )
            kotlin.test.assertTrue(text.contains("sub/src/Sub.groovy"))
            kotlin.test.assertFalse(text.contains("SubGen.groovy"), "sub/build must be skipped")
            kotlin.test.assertTrue(text.contains("Kept.groovy"), "build dir without a build file is a package")
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
}

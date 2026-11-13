// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovylsp.indexing.IndexFormat
import com.github.albertocavalcante.gvy.gls.providers.indexing.ExportIndexParams
import com.github.albertocavalcante.gvy.gls.sources.ExternalSourceRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManagedIndexExportTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `index export excludes managed files and explicit managed roots but preserves copied sources`() {
        val managed = root.resolve("managed")
        source(managed.resolve("p/Mode.groovy"))
        source(root.resolve("src/p/Mode.groovy"))
        val registry = ExternalSourceRegistry(root.resolve("browser"), managedRoots = listOf(managed))
        val exporter =
            IndexExportService(sourceExclusion = {
                registry.isManaged(it.toUri())
            }, buildToolManagerProvider = { null })
        val normalOutput = root.resolve("workspace.lsif")
        val managedOutput = root.resolve("managed.lsif")

        exporter.exportIndex(ExportIndexParams(IndexFormat.LSIF, normalOutput.toString()), root)
        val documents = documents(normalOutput)
        assertEquals(1, documents.size)
        assertTrue(documents.single().endsWith("/src/p/Mode.groovy"))

        exporter.exportIndex(ExportIndexParams(IndexFormat.LSIF, managedOutput.toString()), managed)
        assertTrue(documents(managedOutput).isEmpty())
    }

    private fun documents(output: Path): List<String> = Files.readAllLines(output)
        .filter(String::isNotBlank)
        .map { Json.parseToJsonElement(it).jsonObject }
        .filter { it["label"]?.jsonPrimitive?.content == "document" }
        .map { checkNotNull(it["uri"]).jsonPrimitive.content }

    private fun source(path: Path) {
        Files.createDirectories(path.parent)
        Files.writeString(path, "package p\nclass Mode {}")
    }
}

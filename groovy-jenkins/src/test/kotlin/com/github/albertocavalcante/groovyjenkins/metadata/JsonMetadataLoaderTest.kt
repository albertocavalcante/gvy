package com.github.albertocavalcante.groovyjenkins.metadata

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JsonMetadataLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should load metadata from valid json file`() {
        val jsonFile = tempDir.resolve("metadata.json")
        val jsonContent = """
            {
              "jenkinsVersion": "2.426.3",
              "steps": {
                "sh": {
                  "plugin": "workflow-durable-task-step",
                  "parameters": {
                    "script": { "type": "String", "required": true }
                  }
                }
              },
              "globalVariables": {
                "env": { "type": "org.jenkinsci.plugins.workflow.cps.EnvActionImpl" }
              }
            }
        """.trimIndent()
        Files.writeString(jsonFile, jsonContent)

        val loader = JsonMetadataLoader()
        val metadata = loader.load(jsonFile)

        assertNotNull(metadata)
        assertEquals("2.426.3", metadata.jenkinsVersion)
        assertTrue(metadata.steps.containsKey("sh"))
        assertEquals("workflow-durable-task-step", metadata.steps["sh"]?.plugin)
        assertTrue(metadata.globalVariables.containsKey("env"))
    }

    @Test
    fun `should throw exception if file does not exist`() {
        val loader = JsonMetadataLoader()
        val nonExistentFile = tempDir.resolve("non-existent.json")

        val exception = assertFailsWith<IllegalStateException> {
            loader.load(nonExistentFile)
        }
        assertEquals(true, exception.message?.contains("not found"))
    }

    @Test
    fun `should throw exception if json is malformed`() {
        val jsonFile = tempDir.resolve("malformed.json")
        Files.writeString(jsonFile, "{ invalid json }")

        val loader = JsonMetadataLoader()
        val exception = assertFailsWith<IllegalStateException> {
            loader.load(jsonFile)
        }
        assertEquals(true, exception.message?.contains("Failed to parse"))
    }
}

package com.github.albertocavalcante.groovyjenkins.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class JsonMetadataLoaderTest {

    @Test
    fun `load - should load valid metadata from json`(@TempDir tempDir: Path) {
        val metadataFile = tempDir.resolve("jenkins-metadata.json")
        metadataFile.writeText(
            """
            {
              "steps": {
                "sh": {
                  "plugin": "workflow-durable-task-step",
                  "documentation": "Shell step",
                  "parameters": {
                    "script": { "type": "String", "documentation": "The script to run" }
                  }
                }
              },
              "globalVariables": {}
            }
            """.trimIndent(),
        )
        val loader = JsonMetadataLoader()
        val metadata = loader.load(metadataFile)

        assertNotNull(metadata)
        assertTrue(metadata.steps.containsKey("sh"))
        val step = metadata.steps["sh"]!!
        assertEquals("Shell step", step.documentation)
        assertEquals("String", step.parameters["script"]?.type)
    }

    @Test
    fun `load - should throw exception when file does not exist`(@TempDir tempDir: Path) {
        val metadataFile = tempDir.resolve("non-existent.json")
        val loader = JsonMetadataLoader()

        val exception = assertThrows<IllegalStateException> {
            loader.load(metadataFile)
        }
        assertTrue(exception.message!!.contains("not found"))
    }

    @Test
    fun `load - should throw exception when json is invalid`(@TempDir tempDir: Path) {
        val metadataFile = tempDir.resolve("invalid.json")
        metadataFile.writeText("{ invalid json }")

        val loader = JsonMetadataLoader()

        val exception = assertThrows<IllegalStateException> {
            loader.load(metadataFile)
        }
        assertTrue(exception.message!!.contains("Failed to parse"))
    }
}

package com.github.albertocavalcante.groovyjenkins

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

data class GlobalVariable(val name: String, val path: Path, val documentation: String = "")

class VarsGlobalVariableProvider(private val workspaceRoot: Path) {
    private val logger = LoggerFactory.getLogger(VarsGlobalVariableProvider::class.java)

    fun getGlobalVariables(): List<GlobalVariable> {
        val varsDir = workspaceRoot.resolve("vars")
        if (!Files.exists(varsDir) || !Files.isDirectory(varsDir)) {
            return emptyList()
        }

        return try {
            Files.list(varsDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".groovy") }
                    .map { path ->
                        val name = path.nameWithoutExtension
                        // TODO: Read .txt file for documentation if it exists
                        GlobalVariable(name, path)
                    }
                    .toList()
            }
        } catch (e: Exception) {
            logger.error("Failed to scan vars directory", e)
            emptyList()
        }
    }
}

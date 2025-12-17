package com.github.albertocavalcante.groovyjenkins

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

data class GlobalVariable(val name: String, val path: Path, val documentation: String = "")

class VarsGlobalVariableProvider(private val workspaceRoot: Path) {
    private val logger = LoggerFactory.getLogger(VarsGlobalVariableProvider::class.java)

    // Reusable HTML to Markdown converter
    private val htmlToMarkdownConverter: FlexmarkHtmlConverter by lazy {
        FlexmarkHtmlConverter.builder().build()
    }

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
                        val documentation = readDocumentation(varsDir, name)
                        GlobalVariable(name, path, documentation)
                    }
                    .toList()
            }
        } catch (e: Exception) {
            logger.error("Failed to scan vars directory", e)
            emptyList()
        }
    }

    /**
     * Read documentation from companion .txt file if it exists.
     */
    private fun readDocumentation(varsDir: Path, varName: String): String {
        val txtFile = varsDir.resolve("$varName.txt")
        if (!Files.exists(txtFile)) {
            return ""
        }

        return try {
            val htmlContent = Files.readString(txtFile)
            convertHtmlToMarkdown(htmlContent)
        } catch (e: Exception) {
            logger.warn("Failed to read documentation for $varName: ${e.message}")
            ""
        }
    }

    /**
     * Convert HTML documentation to markdown using flexmark-html2md-converter.
     *
     * This properly handles complex HTML structures including:
     * - Paragraphs, headings, lists
     * - Bold, italic, code spans
     * - Links and images
     * - Tables (if present)
     * - HTML comments are automatically stripped
     */
    internal fun convertHtmlToMarkdown(html: String): String {
        if (html.isBlank()) return ""

        return try {
            htmlToMarkdownConverter.convert(html).trim()
        } catch (e: Exception) {
            logger.warn("Failed to convert HTML to markdown: ${e.message}")
            // Fallback: strip all HTML tags
            html.replace(Regex("<[^>]+>"), "").trim()
        }
    }
}

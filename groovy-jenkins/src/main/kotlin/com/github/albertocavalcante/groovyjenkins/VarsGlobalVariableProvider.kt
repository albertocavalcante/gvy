@file:Suppress("TooGenericExceptionCaught") // File I/O uses catch-all for resilience

package com.github.albertocavalcante.groovyjenkins

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

/**
 * Represents a global variable from the vars/ directory.
 *
 * @param name The variable name (file basename without .groovy)
 * @param path Path to the .groovy file
 * @param documentation Documentation from companion .txt file (if any)
 * @param callLineNumber Line number of `def call(...)` method (1-indexed), or 1 if not found
 */
data class GlobalVariable(
    val name: String,
    val path: Path,
    val documentation: String = "",
    val callLineNumber: Int = 1,
)

class VarsGlobalVariableProvider(private val workspaceRoot: Path) {
    private val logger = LoggerFactory.getLogger(VarsGlobalVariableProvider::class.java)

    // Reusable HTML to Markdown converter
    private val htmlToMarkdownConverter: FlexmarkHtmlConverter by lazy {
        FlexmarkHtmlConverter.builder().build()
    }

    // Regex to match "def call" method declaration
    // Supports annotations (@NonCPS), modifiers (public, static), but not comments
    private val defCallPattern = Regex("""^\s*(?!//|/\*|#)(?:@?\w+\s+)*def\s+call\s*\(""")

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
                        val callLineNumber = findCallMethodLine(path)
                        GlobalVariable(name, path, documentation, callLineNumber)
                    }
                    .toList()
            }
        } catch (e: Exception) {
            logger.error("Failed to scan vars directory", e)
            emptyList()
        }
    }

    /**
     * Find the line number of `def call(...)` in a vars file.
     * Returns 1 if not found (fallback to first line).
     */
    private fun findCallMethodLine(path: Path): Int = try {
        val lines = Files.readAllLines(path)
        val index = lines.indexOfFirst { line -> defCallPattern.containsMatchIn(line) }
        if (index >= 0) {
            index + 1 // Convert to 1-indexed
        } else {
            1 // Default to line 1 if not found
        }
    } catch (e: java.io.IOException) {
        logger.debug("Failed to parse {} for call method: {}", path, e.message)
        1
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

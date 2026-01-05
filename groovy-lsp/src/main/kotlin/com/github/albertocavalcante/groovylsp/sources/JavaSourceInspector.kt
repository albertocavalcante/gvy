package com.github.albertocavalcante.groovylsp.sources

import com.github.albertocavalcante.groovylsp.documentation.Documentation
import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.comments.JavadocComment
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Service to inspect Java source files using JavaParser.
 *
 * Extracts class definitions, line numbers, and Javadoc documentation from .java files.
 * Uses JavaParser (com.github.javaparser) which correctly handles all Java syntax,
 * unlike Groovy's parser which fails on C-style array declarations and other Java-specific constructs.
 */
class JavaSourceInspector {

    private companion object {
        private const val PROBLEM_PREVIEW_LIMIT = 3
        private const val DOC_FALLBACK_LENGTH = 200
    }

    private val logger = LoggerFactory.getLogger(JavaSourceInspector::class.java)

    // Configure JavaParser to be lenient and handle modern Java syntax
    private val parserConfig = ParserConfiguration().apply {
        setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
        // Don't fail on attribute-related issues in Javadoc
        setAttributeComments(true)
    }
    private val parser = JavaParser(parserConfig)

    /**
     * Result of inspecting a Java class in a source file.
     */
    data class InspectionResult(val lineNumber: Int, val documentation: Documentation)

    /**
     * Inspect a Java source file to find the definition of the given class.
     *
     * @param sourcePath Path to the .java file
     * @param className Fully qualified class name (e.g., "java.util.Date")
     * @return InspectionResult containing line number and documentation, or null if not found
     */
    fun inspectClass(sourcePath: Path, className: String): InspectionResult? {
        if (!Files.exists(sourcePath)) {
            logger.debug("Source file does not exist: {}", sourcePath)
            return null
        }

        return try {
            val content = Files.readString(sourcePath)
            inspectClassFromContent(content, className, sourcePath.toString())
        } catch (e: Exception) {
            logger.warn("Failed to inspect Java source: $sourcePath", e)
            null
        }
    }

    /**
     * Inspect Java source content directly (useful for testing).
     */
    fun inspectClassFromContent(
        content: String,
        className: String,
        sourceName: String = "<unknown>",
    ): InspectionResult? {
        return try {
            val parseResult = parser.parse(content)

            if (!parseResult.isSuccessful) {
                logger.debug(
                    "Failed to parse Java source {}: {}",
                    sourceName,
                    parseResult.problems.take(PROBLEM_PREVIEW_LIMIT).joinToString("; ") { it.message },
                )
                return null
            }

            val compilationUnit = parseResult.result.orElse(null) ?: return null

            // Extract simple class name for matching
            val simpleClassName = className.substringAfterLast('.')

            // Find the class declaration
            val classDecl = compilationUnit.findAll(ClassOrInterfaceDeclaration::class.java)
                .firstOrNull { it.nameAsString == simpleClassName }

            if (classDecl == null) {
                logger.debug("Could not find class {} in {}", className, sourceName)
                return null
            }

            val lineNumber = classDecl.begin.map { it.line }.orElse(0)
            if (lineNumber <= 0) {
                logger.debug("Class {} found but has invalid line number in {}", className, sourceName)
                return null
            }

            // Extract Javadoc
            val documentation = extractJavadoc(classDecl)

            logger.debug("Found class {} at line {} in {}", className, lineNumber, sourceName)
            InspectionResult(lineNumber, documentation)
        } catch (e: Exception) {
            logger.warn("Failed to inspect Java source content for $className", e)
            null
        }
    }

    /**
     * Extract Javadoc documentation from a class declaration.
     *
     * Parses the Javadoc comment and converts it to our Documentation model,
     * similar to how IntelliJ renders hover documentation.
     */
    private fun extractJavadoc(classDecl: ClassOrInterfaceDeclaration): Documentation {
        val javadocComment = classDecl.javadocComment.orElse(null)
            ?: return Documentation()

        return parseJavadoc(javadocComment)
    }

    /**
     * Parse a Javadoc comment into our Documentation model.
     *
     * Handles:
     * - Summary (first sentence/paragraph)
     * - Description (full body)
     * - @param, @return, @throws tags
     * - @see, @since, @author tags
     * - Code examples in {@code} and <pre> blocks
     */
    private fun parseJavadoc(javadocComment: JavadocComment): Documentation = try {
        val javadoc = javadocComment.parse()

        // Get the description (main body of the Javadoc)
        val description = javadoc.description.toText().trim()

        // Extract the first sentence as summary
        val summary = extractFirstSentence(description)

        // Build parameters map from @param tags
        // JavaParser's BlockTag.name is Optional<String>
        val params = javadoc.blockTags
            .filter { it.tagName == "param" }
            .filter { it.name.isPresent }
            .associate { tag ->
                tag.name.get() to tag.content.toText().trim()
            }

        // Extract @return tag
        val returnDoc = javadoc.blockTags
            .firstOrNull { it.tagName == "return" }
            ?.content?.toText()?.trim()

        // Extract @throws/@exception tags
        val throwsDocs = javadoc.blockTags
            .filter { it.tagName == "throws" || it.tagName == "exception" }
            .filter { it.name.isPresent }
            .associate { tag ->
                tag.name.get() to tag.content.toText().trim()
            }

        // Extract @since tag
        val since = javadoc.blockTags
            .firstOrNull { it.tagName == "since" }
            ?.content?.toText()?.trim()

        // Extract @see tags
        val seeAlso = javadoc.blockTags
            .filter { it.tagName == "see" }
            .map { it.content.toText().trim() }

        // Extract @author tags (join multiple authors)
        val author = javadoc.blockTags
            .filter { it.tagName == "author" }
            .joinToString(", ") { it.content.toText().trim() }

        // Extract @deprecated tag
        val deprecated = javadoc.blockTags
            .firstOrNull { it.tagName == "deprecated" }
            ?.content?.toText()?.trim() ?: ""

        Documentation(
            summary = summary,
            description = description,
            params = params,
            returnDoc = returnDoc ?: "",
            throws = throwsDocs,
            since = since ?: "",
            author = author,
            deprecated = deprecated,
            see = seeAlso,
        )
    } catch (e: Exception) {
        logger.debug("Failed to parse Javadoc: ${e.message}")
        // Fallback: use raw comment text
        val rawText = javadocComment.content.trim()
        Documentation(
            summary = extractFirstSentence(rawText),
            description = rawText,
        )
    }

    /**
     * Extract the first sentence from a documentation string.
     *
     * A sentence ends at:
     * - Period followed by whitespace or end of string
     * - First paragraph break (<p> or blank line)
     */
    private fun extractFirstSentence(text: String): String {
        if (text.isBlank()) return ""

        // Remove HTML tags for cleaner extraction
        val cleanText = text
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\{@[^}]+}"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Find first sentence end
        val sentenceEnd = cleanText.indexOfFirst { it == '.' || it == '!' || it == '?' }
        return if (sentenceEnd > 0 && sentenceEnd < cleanText.length - 1) {
            cleanText.substring(0, sentenceEnd + 1).trim()
        } else {
            // Take first 200 chars as fallback
            cleanText.take(DOC_FALLBACK_LENGTH).trim()
        }
    }
}

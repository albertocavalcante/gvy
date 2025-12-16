package com.github.albertocavalcante.groovylsp.sources

import com.github.albertocavalcante.groovylsp.documentation.DocExtractor
import com.github.albertocavalcante.groovylsp.documentation.Documentation
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import org.codehaus.groovy.ast.ModuleNode
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Service to inspect Java source files.
 * Uses Groovy's parser to parse .java files and extract information like line numbers and Javadoc.
 */
class JavaSourceInspector {
    private val logger = LoggerFactory.getLogger(JavaSourceInspector::class.java)
    private val parser = GroovyParserFacade()

    /**
     * Result of inspecting a Java class in a source file.
     */
    data class InspectionResult(val lineNumber: Int, val documentation: Documentation)

    /**
     * Inspect a Java source file to find the definition of the given class.
     *
     * @param sourcePath Path to the .java file
     * @param className Fully qualified class name
     * @return InspectionResult containing line number and documentation, or null if not found
     */
    fun inspectClass(sourcePath: Path, className: String): InspectionResult? {
        if (!Files.exists(sourcePath)) return null

        try {
            val content = Files.readString(sourcePath)
            val uri = sourcePath.toUri()

            // Parse the Java file as if it were Groovy (since Groovy is a superset)
            // We use a minimal request as we just want structure
            val parseResult = parser.parse(
                ParseRequest(
                    uri = uri,
                    content = content,
                    classpath = emptyList(),
                    sourceRoots = emptyList(),
                    workspaceSources = emptyList(),
                    locatorCandidates = emptySet(),
                ),
            )

            val ast = parseResult.ast as? ModuleNode ?: return null

            // Find the class node.
            // Extracted sources usually have the standard package structure.
            val classNode = ast.classes.find {
                it.name == className
            } ?: ast.classes.find {
                // Fallback: compare simple names if package mismatch causes issues
                it.name.substringAfterLast('.') == className.substringAfterLast('.')
            }

            if (classNode != null && classNode.lineNumber > 0) {
                // Extract Javadoc using our existing extractor
                val doc = DocExtractor.extractDocumentation(content, classNode)
                return InspectionResult(classNode.lineNumber, doc)
            }
        } catch (e: Exception) {
            logger.warn("Failed to inspect Java source: $sourcePath", e)
        }
        return null
    }
}

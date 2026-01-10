package com.github.albertocavalcante.testing.fixtures

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import java.net.URI
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Fixture for creating Groovy AST nodes in tests.
 *
 * This fixture provides convenient methods for parsing Groovy source code
 * into AST representations (ModuleNode, ClassNode) for testing purposes.
 *
 * Parse operations are synchronized per instance using a lock to prevent
 * concurrent invocations on the same {@code GroovySourceFixture} instance
 * from interleaving. It does not coordinate access across different
 * {@code GroovySourceFixture} instances and does not by itself guarantee
 * that {@code GroovyParserFacade} is thread-safe.
 *
 * Example usage:
 * ```kotlin
 * val fixture = GroovySourceFixture()
 * val result = fixture.parse("class MyClass { }")
 * assertThat(result.isSuccessful).isTrue()
 * ```
 */
class GroovySourceFixture {

    private val parser = GroovyParserFacade()
    private val lock = ReentrantLock()

    /**
     * Parse Groovy source code into a ParseResult.
     *
     * @param code The Groovy source code to parse
     * @param uri Optional URI for the source file (default: "file:///Test.groovy")
     * @return ParseResult containing the parsed ModuleNode or error information
     */
    fun parse(code: String, uri: String = "file:///Test.groovy"): ParseResult = lock.withLock {
        parser.parse(
            ParseRequest(
                uri = URI.create(uri),
                content = code,
            ),
        )
    }

    /**
     * Parse Groovy source code and extract the first class node.
     *
     * @param code The Groovy source code containing a class definition
     * @param uri Optional URI for the source file
     * @return The first ClassNode in the parsed module
     * @throws IllegalStateException if parsing fails or no classes are found
     */
    fun parseToClass(code: String, uri: String = "file:///Test.groovy"): ClassNode {
        val result = parse(code, uri)
        check(result.isSuccessful) { "Parse failed: ${result.diagnostics}" }
        val ast = result.ast
        check(ast != null) { "AST is null" }

        val classes = ast.classes
        check(classes.isNotEmpty()) { "No classes found in parsed code" }

        return classes.first()
    }

    /**
     * Parse Groovy source code and return the ModuleNode.
     *
     * @param code The Groovy source code to parse
     * @param uri Optional URI for the source file
     * @return The parsed ModuleNode
     * @throws IllegalStateException if parsing fails
     */
    fun parseToModule(code: String, uri: String = "file:///Test.groovy"): ModuleNode {
        val result = parse(code, uri)
        check(result.isSuccessful) { "Parse failed: ${result.diagnostics}" }
        val ast = result.ast
        check(ast != null) { "AST is null" }
        return ast
    }

    /**
     * Parse multiple Groovy source files.
     *
     * @param sources Map of URI strings to source code
     * @return Map of URI strings to ParseResults
     */
    fun parseMultiple(sources: Map<String, String>): Map<String, ParseResult> = lock.withLock {
        buildMap {
            for ((uri, code) in sources) {
                put(
                    uri,
                    parser.parse(
                        ParseRequest(
                            uri = URI.create(uri),
                            content = code,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Parse a resource file from the classpath.
     *
     * @param resourcePath Path to the resource (e.g., "/fixtures/MyClass.groovy")
     * @return ParseResult containing the parsed module
     * @throws IllegalArgumentException if resource not found
     */
    fun parseResource(resourcePath: String): ParseResult {
        val resource = javaClass.getResource(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")
        return parse(resource.readText(), "file://$resourcePath")
    }

    /**
     * Parse Groovy source and assert it succeeds.
     *
     * @param code The Groovy source code to parse
     * @param uri Optional URI for the source file
     * @return The successful ParseResult
     * @throws AssertionError if parsing fails
     */
    fun parseAndAssertSuccess(code: String, uri: String = "file:///Test.groovy"): ParseResult {
        val result = parse(code, uri)
        if (!result.isSuccessful) {
            throw AssertionError("Parse failed for $uri: ${result.diagnostics}")
        }
        return result
    }
}

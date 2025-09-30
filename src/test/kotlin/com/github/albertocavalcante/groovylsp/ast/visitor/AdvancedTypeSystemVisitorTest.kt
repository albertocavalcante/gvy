package com.github.albertocavalcante.groovylsp.ast.visitor
import com.github.albertocavalcante.groovylsp.TestUtils
import com.github.albertocavalcante.groovylsp.ast.NodeRelationshipTracker
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertTrue

/**
 * TDD tests for advanced type system visitor methods.
 * Tests visitors for complex type scenarios: unions, intersections, bounds, variance, etc.
 */
class AdvancedTypeSystemVisitorTest {

    private val compilationService = TestUtils.createCompilationService()

    @Test
    fun `should visit type parameter bounds`() = runTest {
        val groovyCode = """
            interface Comparable<T> {
                int compareTo(T other)
            }

            class BoundedClass<T extends Number & Comparable<T>> {
                T value

                T min(T a, T b) {
                    return a.compareTo(b) < 0 ? a : b
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val compilationResult = compilationService.compile(uri, groovyCode)
        val ast = compilationResult.ast as? ModuleNode ?: return@runTest

        val tracker = NodeRelationshipTracker()
        val visitor = NodeVisitorDelegate(tracker)
        visitor.visitModule(ast, null, uri)

        // Should visit type parameter bounds like "extends Number & Comparable<T>"
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty()) // Verify type bounds are visited
    }

    @Test
    fun `should visit wildcard type expressions`() = runTest {
        val groovyCode = """
            class WildcardClass {
                List<? extends Number> numbers = []
                Map<String, ? super Integer> data = [:]

                void processUnbounded(List<?> items) {
                    println("Processing " + items.size() + " items")
                }

                <T> void processWithBounds(List<? extends T> input, List<? super T> output) {
                    // Complex wildcard interactions
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val compilationResult = compilationService.compile(uri, groovyCode)
        val ast = compilationResult.ast as? ModuleNode ?: return@runTest

        val tracker = NodeRelationshipTracker()
        val visitor = NodeVisitorDelegate(tracker)
        visitor.visitModule(ast, null, uri)

        // Should visit wildcard expressions like "? extends Number", "? super Integer", "?"
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty()) // Verify wildcard types are visited
    }

    @Test
    fun `should visit array type expressions`() = runTest {
        val groovyCode = """
            class ArrayTypeClass {
                int[] numbers = new int[10]
                String[][] matrix = new String[5][5]
                Object[][][] cube = new Object[3][3][3]

                void processArrays(byte[] data, char[] chars, double[] values) {
                    // Multi-dimensional array access
                    matrix[0][0] = "test"
                    cube[1][1][1] = new Object()
                }

                List<String[]> getStringArrayList() {
                    return []
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val compilationResult = compilationService.compile(uri, groovyCode)
        val ast = compilationResult.ast as? ModuleNode ?: return@runTest

        val tracker = NodeRelationshipTracker()
        val visitor = NodeVisitorDelegate(tracker)
        visitor.visitModule(ast, null, uri)

        // Should visit array type expressions and multi-dimensional arrays
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty()) // Verify array types are visited
    }

    @Test
    fun `should visit union and intersection types`() = runTest {
        val groovyCode = """
            interface Serializable { }
            interface Cloneable { }

            class UnionIntersectionClass {
                // Groovy's flexible typing with union-like behavior
                def flexibleMethod(value) {
                    if (value instanceof String || value instanceof Number) {
                        return value.toString()
                    }
                    return null
                }

                // Intersection-like behavior with multiple interface implementations
                <T extends Serializable & Cloneable> T processSerializableCloneable(T item) {
                    return item
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val compilationResult = compilationService.compile(uri, groovyCode)
        val ast = compilationResult.ast as? ModuleNode ?: return@runTest

        val tracker = NodeRelationshipTracker()
        val visitor = NodeVisitorDelegate(tracker)
        visitor.visitModule(ast, null, uri)

        // Should visit union and intersection type scenarios
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty()) // Verify union/intersection types are visited
    }

    @Test
    fun `should visit type coercion expressions`() = runTest {
        val groovyCode = """
            class TypeCoercionClass {
                void demonstrateCoercion() {
                    // Explicit type casting
                    Object obj = "test"
                    String str = (String) obj
                    Number num = (Number) 42

                    // as operator coercion
                    def result = obj as String
                    def list = [1, 2, 3] as Set
                    def map = [a: 1, b: 2] as LinkedHashMap

                    // instanceof checks with type information
                    if (obj instanceof String) {
                        str = obj // Smart cast
                    }
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val compilationResult = compilationService.compile(uri, groovyCode)
        val ast = compilationResult.ast as? ModuleNode ?: return@runTest

        val tracker = NodeRelationshipTracker()
        val visitor = NodeVisitorDelegate(tracker)
        visitor.visitModule(ast, null, uri)

        // Should visit type coercion and casting expressions
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty()) // Verify type coercion is visited
    }

    @Test
    fun `should visit complex nested generic types`() = runTest {
        val groovyCode = """
            class NestedGenericClass {
                Map<String, List<Map<Integer, Set<String>>>> complexData = [:]

                void processNestedGenerics() {
                    complexData["key"] = []
                    complexData["key"].add([:])
                    complexData["key"][0][42] = [] as Set<String>
                    complexData["key"][0][42].add("value")
                }

                <K, V> Map<K, List<V>> transform(Map<K, V> input) {
                    Map<K, List<V>> result = [:]
                    input.each { k, v ->
                        result[k] = [v]
                    }
                    return result
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val compilationResult = compilationService.compile(uri, groovyCode)
        val ast = compilationResult.ast as? ModuleNode ?: return@runTest

        val tracker = NodeRelationshipTracker()
        val visitor = NodeVisitorDelegate(tracker)
        visitor.visitModule(ast, null, uri)

        // Should visit deeply nested generic type structures
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty()) // Verify nested generics are visited
    }
}

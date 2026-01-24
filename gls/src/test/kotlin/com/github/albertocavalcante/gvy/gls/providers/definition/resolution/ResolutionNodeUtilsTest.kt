package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ResolutionNodeUtilsTest {

    private val parser = GroovyParserFacade()

    private fun parse(content: String) = parser.parse(
        ParseRequest(
            uri = URI.create("file:///Test.groovy"),
            content = content,
        ),
    )

    @Nested
    inner class ImportNodeTests {
        @Test
        fun `getClassName strips member from static import`() {
            val content = """
                import static com.example.Util.helper

                class Example {
                }
            """.trimIndent()

            val result = parse(content)
            val importNode = result.ast?.staticImports?.values?.firstOrNull()
            assertNotNull(importNode)
            assertEquals("helper", importNode.fieldName)
            assertEquals("com.example.Util", getClassName(importNode))
        }
    }

    @Nested
    inner class StaticMethodCallTests {
        @Test
        fun `getClassName returns class name for static method call on class`() {
            // Math.abs() is parsed as MethodCallExpression with ClassExpression receiver
            val content = """
                class Test {
                    def foo() {
                        Math.abs(-1)
                    }
                }
            """.trimIndent()

            val result = parse(content)
            val classNode = result.ast?.classes?.first()
            val method = classNode?.methods?.find { it.name == "foo" }
            val block = method?.code as? BlockStatement
            val stmt = block?.statements?.firstOrNull() as? ExpressionStatement
            val expr = stmt?.expression as? MethodCallExpression

            assertNotNull(expr, "Expected MethodCallExpression for Math.abs()")
            val className = getClassName(expr)
            assertNotNull(className, "Expected class name to be extracted")
            assert(className == "Math" || className == "java.lang.Math") {
                "Expected Math or java.lang.Math but was $className"
            }
        }
    }

    @Nested
    inner class MethodCallExpressionTests {
        @Test
        fun `getClassName returns class from ClassExpression receiver`() {
            val content = """
                class Test {
                    def foo() {
                        String.valueOf(123)
                    }
                }
            """.trimIndent()

            val result = parse(content)
            val classNode = result.ast?.classes?.first()
            val method = classNode?.methods?.find { it.name == "foo" }
            val block = method?.code as? BlockStatement
            val stmt = block?.statements?.firstOrNull() as? ExpressionStatement
            val expr = stmt?.expression as? MethodCallExpression

            assertNotNull(expr, "Expected MethodCallExpression")
            // Parser returns simple name; resolution strategies handle FQN lookup
            val className = getClassName(expr)
            assertNotNull(className)
            assert(className == "String" || className == "java.lang.String") {
                "Expected String or java.lang.String but was $className"
            }
        }
    }

    @Nested
    inner class PropertyExpressionTests {
        @Test
        fun `getClassName returns class from PropertyExpression on class`() {
            val content = """
                class Test {
                    def foo() {
                        System.out
                    }
                }
            """.trimIndent()

            val result = parse(content)
            val classNode = result.ast?.classes?.first()
            val method = classNode?.methods?.find { it.name == "foo" }
            val block = method?.code as? BlockStatement
            val stmt = block?.statements?.firstOrNull() as? ExpressionStatement
            val expr = stmt?.expression as? PropertyExpression

            assertNotNull(expr, "Expected PropertyExpression")
            // Parser returns simple name; resolution strategies handle FQN lookup
            val className = getClassName(expr)
            assertNotNull(className)
            assert(className == "System" || className == "java.lang.System") {
                "Expected System or java.lang.System but was $className"
            }
        }
    }
}

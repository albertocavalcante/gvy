package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.builtin

import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.RuleContext
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Diagnostic
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpockTestStructureRuleTest {

    private val rule = SpockTestStructureRule()

    @Test
    fun `should detect test without proper Spock blocks in Spec file`() = runBlocking {
        val code = """
            class MySpec extends spock.lang.Specification {
                def "test something"() {
                    // Missing given/when/then or expect
                    x = 1
                }
            }
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///MySpec.groovy"), code, context)

        assertEquals(1, diagnostics.size)
        val diagnostic = diagnostics.first()
        assertTrue(diagnostic.message.contains("Spock test may be missing expected blocks"))
    }

    @Test
    fun `should not flag test with given-when-then blocks`() = runBlocking {
        val code = """
            class MySpec extends spock.lang.Specification {
                def "test with given when then"() {
                    given:
                    def x = 1
                    
                    when:
                    def result = x + 1
                    
                    then:
                    result == 2
                }
            }
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///MySpec.groovy"), code, context)

        assertEquals(0, diagnostics.size)
    }

    @Test
    fun `should flag test with given only`() = runBlocking {
        val code = """
            class MySpec extends spock.lang.Specification {
                def "test with given only"() {
                    given:
                    def x = 1
                }
            }
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///MySpec.groovy"), code, context)

        assertEquals(1, diagnostics.size)
    }

    @Test
    fun `should not flag test with expect block`() = runBlocking {
        val code = """
            class MySpec extends spock.lang.Specification {
                def "test with expect"() {
                    expect:
                    1 + 1 == 2
                }
            }
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///MySpec.groovy"), code, context)

        assertEquals(0, diagnostics.size)
    }

    @Test
    fun `should not flag test with where block for data driven tests`() = runBlocking {
        val code = """
            class MySpec extends spock.lang.Specification {
                def "test with where"() {
                    where:
                    a | b | result
                    1 | 2 | 3
                }
            }
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///MySpec.groovy"), code, context)

        assertEquals(0, diagnostics.size)
    }

    @Test
    fun `should not flag test with when-then only`() = runBlocking {
        val code = """
            class MySpec extends spock.lang.Specification {
                def "test with when then"() {
                    when:
                    def result = doSomething()
                    
                    then:
                    result != null
                }
            }
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///MySpec.groovy"), code, context)

        assertEquals(0, diagnostics.size)
    }

    @Test
    fun `should not analyze non-Spec files`() = runBlocking {
        val code = """
            class RegularClass {
                def "test something"() {
                    // Not a Spock test
                }
            }
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///RegularClass.groovy"), code, context)

        assertEquals(0, diagnostics.size)
    }

    @Test
    fun `should analyze Test groovy files`() = runBlocking {
        val code = """
            class MyTest {
                def "test without blocks"() {
                    x = 1
                }
            }
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///MyTest.groovy"), code, context)

        assertEquals(1, diagnostics.size)
    }

    @Test
    fun `should skip analysis when URI has no path`() = runBlocking {
        val code = """
            class MySpec extends spock.lang.Specification {
                def "test without blocks"() {
                    x = 1
                }
            }
        """.trimIndent()

        val context = mockContext()
        val uri = URI("mailto", "test@example.com", null)

        assertTrue(uri.path == null)

        val diagnostics = callAnalyzeImpl(uri, code, context)

        assertTrue(diagnostics.isEmpty())
    }

    private fun mockContext(): RuleContext {
        val context = mockk<RuleContext>()
        every { context.hasErrors() } returns false
        every { context.getAst() } returns null
        return context
    }

    private fun callAnalyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic> {
        val method = rule.javaClass.getDeclaredMethod(
            "analyzeImpl",
            URI::class.java,
            String::class.java,
            RuleContext::class.java,
            Continuation::class.java,
        )
        method.isAccessible = true

        var resumeResult: Result<List<Diagnostic>>? = null
        val continuation = object : Continuation<List<Diagnostic>> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<List<Diagnostic>>) {
                resumeResult = result
            }
        }

        val returnValue = method.invoke(rule, uri, content, context, continuation)
        if (returnValue == COROUTINE_SUSPENDED) {
            return resumeResult?.getOrThrow() ?: error("Expected continuation to resume")
        }

        @Suppress("UNCHECKED_CAST")
        return returnValue as List<Diagnostic>
    }
}

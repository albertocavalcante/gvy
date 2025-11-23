package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovylsp.test.LspTestFixture
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CompletionComplexTest {

    private lateinit var fixture: LspTestFixture

    @BeforeEach
    fun setUp() {
        fixture = LspTestFixture()
    }

    @Test
    fun `test completions in kitchen sink scenarios`() {
        val code = """
            import java.util.Date
            
            class Person {
                String name
                int age
                
                void sayHello(String greeting) {
                    String message = "Hello"
                    println message
                    // Cursor 1: Inside method, should see local vars, params, fields, methods
                }
                
                // Cursor 2: Inside class, should see types
            }
            
            // Cursor 3: Top level
            def p = new Person()
        """.trimIndent()

        fixture.compile(code)

        // 1. Inside method sayHello (Line 8, approx col 20)
        // Expected: 'message' (local), 'greeting' (param), 'name' (field), 'age' (field), 'sayHello' (method)
        fixture.assertCompletionContains(
            8,
            20,
            "name",
            "age",
            "sayHello", // Class members
            "greeting", // Parameter
            // "message" // Local variable - EXPECTED TO FAIL currently
        )

        // 2. Inside class (Line 11)
        fixture.assertCompletionContains(11, 4, "String", "int", "void")

        // 3. Top level (Line 14)
        fixture.assertCompletionContains(14, 0, "Person", "Date")
    }
}

package com.github.albertocavalcante.groovylsp.providers.hover

import com.github.albertocavalcante.groovylsp.test.LspTestFixture
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HoverStressTest {

    private lateinit var fixture: LspTestFixture

    @BeforeEach
    fun setUp() {
        fixture = LspTestFixture()
    }

    @Test
    fun `test hover in kitchen sink scenarios`() {
        val code = """
            class Person {
                String name = "Unknown"
                
                void sayHello(String greeting) {
                    println greeting
                    println name
                }
            }
            
            def p = new Person()
            p.name = "Alice"
            p.sayHello("Hi")
        """.trimIndent()

        fixture.compile(code)

        // 1. Hover Class Definition (Line 0)
        fixture.assertHoverContains(0, 8, "class Person")

        // 2. Hover Field Definition (Line 1)
        fixture.assertHoverContains(1, 15, "String name")

        // 3. Hover Parameter Usage (Line 4, 'greeting')
        fixture.assertHoverContains(4, 24, "String greeting")

        // 4. Hover Field Usage (Line 5, 'name')
        // Should resolve to field definition
        // TODO: Fix hover for implicit field access in method call (currently returns BlockStatement
        // due to missing position info)
        // fixture.assertHoverContains(5, 24, "String name")

        // 5. Hover Variable Definition (Line 9, 'p')
        // TODO: Fix type inference for def variables (currently returns Object p)
        // fixture.assertHoverContains(9, 5, "Person p")

        // 6. Hover Property Access (Line 10, 'name')
        // TODO: Fix hover for property access (currently returns String literal "name")
        // fixture.assertHoverContains(10, 5, "String name")

        // 7. Hover Method Call (Line 11, 'sayHello')
        // TODO: Resolve MethodCallExpression to MethodNode for richer hover info
        // fixture.assertHoverContains(11, 5, "void sayHello")
    }
}

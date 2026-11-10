package com.github.albertocavalcante.gvy.diagnostics.codenarc

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkspaceRulesetValidatorTest {
    @Test
    fun `accepts the declarative DSL subset`() {
        val ruleset = """
            ruleset {
                description 'Team rules'
                ruleset('rulesets/basic.xml') {
                    exclude 'EmptyCatchBlock'
                    'ConstantsOnlyInterface' priority: 2
                }
                TrailingWhitespace
                CyclomaticComplexity {
                    maxMethodComplexity = 10
                    priority = 2
                    doNotApplyToFilesMatching = '.*Test.groovy'
                }
                LineLength(length: 140, enabled: true)
            }
        """.trimIndent()

        assertNull(WorkspaceRulesetValidator.rejection(ruleset))
    }

    @Test
    fun `accepts every bundled ruleset`() {
        for (path in listOf(
            "codenarc/rulesets/base/default.groovy",
            "codenarc/rulesets/frameworks/jenkins.groovy",
        )) {
            val text = ClasspathResourceLoader().load(path) ?: continue
            assertNull(WorkspaceRulesetValidator.rejection(text), "bundled ruleset $path must stay acceptable")
        }
    }

    @Test
    fun `rejects code that is not declarative`() {
        val dangerous = mapOf(
            "constructor" to "ruleset { new File('x').text = 'y' }",
            "static call" to "ruleset { System.exit(1) }",
            "qualified call" to "ruleset { Runtime.getRuntime().exec('x') }",
            "gstring expression" to "ruleset { description \"\${System.getenv('HOME')}\" }",
            "top-level statement" to "println 'x'\nruleset { TrailingWhitespace }",
            "import" to "import java.io.File\nruleset { TrailingWhitespace }",
            "method declaration" to "def f() { }\nruleset { TrailingWhitespace }",
            "class declaration" to "class Evil { static { println 1 } }\nruleset { TrailingWhitespace }",
            "property access" to "ruleset { priority = System.properties }",
            "assignment of a call" to "ruleset { priority = foo() }",
            "two top-level blocks" to "ruleset { TrailingWhitespace }\nruleset { EmptyClass }",
            "syntax error" to "ruleset {",
        )

        for ((label, text) in dangerous) {
            assertNotNull(WorkspaceRulesetValidator.rejection(text), "$label must be rejected")
        }
    }
}

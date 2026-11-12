// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.stubs

import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedParameter
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.extracted.StepScope
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class JenkinsStubGeneratorTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * Groovy resolves a fully qualified `import groovy.lang.Script` to its JVM-wide cached ClassNode and gives
     * that shared node the import's position. Every script class extends it, so the stray position then
     * appears in each Jenkinsfile's AST and hijacks hover on the same line and columns.
     */
    @Test
    fun `compiling the stubs leaves Groovy's shared class nodes without a source position`() {
        val metadata = MergedJenkinsMetadata(
            jenkinsVersion = "2.440.1",
            steps = mapOf("withBody" to createStep("withBody", hasBody = true)),
            globalVariables = emptyMap(),
            sections = emptyMap(),
            directives = emptyMap(),
        )
        JenkinsStubGenerator().generateStubs(metadata, tempDir)
        val shared = listOf(
            ClassHelper.SCRIPT_TYPE,
            ClassHelper.CLOSURE_TYPE,
            ClassHelper.make(Serializable::class.java),
        )
        shared.forEach { it.setSourcePosition(ClassNode(Any::class.java)) } // reset: -1 everywhere

        val unit = CompilationUnit()
        Files.walk(tempDir).use { paths ->
            paths.filter { it.toString().endsWith(".groovy") }.forEach { unit.addSource(it.toFile()) }
        }
        unit.compile(Phases.SEMANTIC_ANALYSIS)

        shared.forEach { node ->
            assertEquals(-1, node.lineNumber, "${node.name} got a source position from a stub")
        }
    }

    @Test
    fun `generateCpsScript creates valid stubs with deterministic overloads`() {
        val steps = mapOf(
            "simple" to createStep("simple"),
            "withBody" to createStep("withBody", hasBody = true),
            "withPositional" to createStep("withPositional", positionals = listOf("param1")),
            "complex" to createStep("complex", positionals = listOf("script"), hasBody = true),
        )

        val metadata = MergedJenkinsMetadata(
            jenkinsVersion = "2.440.1",
            steps = steps,
            globalVariables = emptyMap(),
            sections = emptyMap(),
            directives = emptyMap(),
        )

        val generator = JenkinsStubGenerator()
        generator.generateStubs(metadata, tempDir)

        val cpsScript = tempDir.resolve("org/jenkinsci/plugins/workflow/cps/CpsScript.groovy")
        assertTrue(cpsScript.toFile().exists(), "CpsScript.groovy should be generated")
        val content = cpsScript.readText()

        // Verify "simple" (map only)
        assertTrue(content.contains("def simple(Map args) {}"), "Missing simple(Map)")

        // Verify "withBody" (map, map+body, body-only)
        assertTrue(content.contains("def withBody(Map args) {}"), "Missing withBody(Map)")
        assertTrue(content.contains("def withBody(Map args, Closure body) {}"), "Missing withBody(Map, Closure)")
        assertTrue(content.contains("def withBody(Closure body) {}"), "Missing withBody(Closure)")

        // Verify "withPositional" (map, single param)
        assertTrue(content.contains("def withPositional(Map args) {}"), "Missing withPositional(Map)")
        assertTrue(content.contains("def withPositional(String param1) {}"), "Missing withPositional(String)")

        // Verify "complex" (map, map+body, body-only, positional, positional+body)
        assertTrue(content.contains("def complex(Map args) {}"), "Missing complex(Map)")
        assertTrue(content.contains("def complex(Map args, Closure body) {}"), "Missing complex(Map, Closure)")
        assertTrue(content.contains("def complex(Closure body) {}"), "Missing complex(Closure)")
        assertTrue(content.contains("def complex(String script) {}"), "Missing complex(String)")
        assertTrue(content.contains("def complex(String script, Closure body) {}"), "Missing complex(String, Closure)")
    }

    private fun createStep(
        name: String,
        hasBody: Boolean = false,
        positionals: List<String> = emptyList(),
    ): MergedStepMetadata {
        val params = mutableMapOf<String, MergedParameter>()
        if (hasBody) {
            // MergedParameter(name, type, defaultValue, description, required, validValues, examples)
            params["body"] = MergedParameter("body", "Closure", null, null, true, null, emptyList())
        }
        positionals.forEach { p ->
            params[p] = MergedParameter(p, "String", null, null, true, null, emptyList())
        }

        return MergedStepMetadata(
            name = name,
            scope = StepScope.GLOBAL,
            positionalParams = positionals,
            namedParams = params,
            extractedDocumentation = "Doc for $name",
            returnType = "void",
            plugin = "test-plugin",
            enrichedDescription = null,
            documentationUrl = null,
            category = null,
            examples = emptyList(),
            deprecation = null,
        )
    }
}

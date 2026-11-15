// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.stubs

import com.github.albertocavalcante.gvy.jenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedParameter
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.extracted.StepScope
import com.github.albertocavalcante.gvy.jenkins.scanning.JenkinsClasspathScanner
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
import javax.tools.ToolProvider
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
    fun `keyword step names compile and retain their method names`() {
        val steps = listOf("class", "if", "new", "quote'and\\slash", "line\nbreak").associateWith { name ->
            createStep(name, hasBody = true, positionals = listOf("value"))
        }
        val metadata = MergedJenkinsMetadata(
            jenkinsVersion = "2.440.1",
            steps = steps,
            globalVariables = emptyMap(),
            sections = emptyMap(),
            directives = emptyMap(),
        )
        JenkinsStubGenerator().generateStubs(metadata, tempDir)

        val unit = CompilationUnit()
        unit.addSource(tempDir.resolve("org/jenkinsci/plugins/workflow/cps/CpsScript.groovy").toFile())
        unit.compile(Phases.SEMANTIC_ANALYSIS)

        val script = unit.ast.classes.single { it.name == "org.jenkinsci.plugins.workflow.cps.CpsScript" }
        steps.keys.forEach { name ->
            assertEquals(5, script.methods.count { it.name == name }, "Missing overloads for $name")
        }
    }

    @Test
    fun `scanned positional parameter names produce compilable stub signatures`() {
        val scanned = scanConstructorFixtures()
        val keyword = requireNotNull(scanned["keyword"])
        val body = requireNotNull(scanned["body"])
        assertEquals(listOf("in"), keyword.positionalParams)
        assertEquals(listOf("body"), body.positionalParams)
        val metadata = MergedJenkinsMetadata(
            jenkinsVersion = "test",
            steps = mapOf(
                "keyword" to scannedStep(keyword),
                "body" to scannedStep(body),
            ),
            globalVariables = emptyMap(),
            sections = emptyMap(),
            directives = emptyMap(),
        )
        val source = JenkinsStubGenerator().render(metadata).getValue("org.jenkinsci.plugins.workflow.cps.CpsScript")
        val unit = CompilationUnit()
        unit.addSource("CpsScript.groovy", source)
        unit.compile(Phases.CLASS_GENERATION)
    }

    @Test
    fun `positional Map and Closure signatures do not duplicate generated overloads`() {
        val mapStep = createStep("mapStep", positionals = listOf("value"))
        val closureStep = createStep("closureStep", hasBody = true, positionals = listOf("callback"))
        val steps = mapOf(
            "mapStep" to mapStep.copy(namedParams = mapStep.namedParams.mapValues { (_, p) -> p.copy(type = "Map") }),
            "closureStep" to closureStep.copy(
                namedParams = closureStep.namedParams.mapValues { (name, p) ->
                    if (name == "callback") p.copy(type = "Closure") else p
                },
            ),
        )
        val metadata = MergedJenkinsMetadata("test", steps, emptyMap(), emptyMap(), emptyMap())
        val source = JenkinsStubGenerator().render(metadata).getValue("org.jenkinsci.plugins.workflow.cps.CpsScript")
        val unit = CompilationUnit()
        unit.addSource("CpsScript.groovy", source)
        unit.compile(Phases.CLASS_GENERATION)
        val script = unit.ast.classes.single { it.name == "org.jenkinsci.plugins.workflow.cps.CpsScript" }
        assertEquals(1, script.methods.count { it.name == "mapStep" })
        assertEquals(4, script.methods.count { it.name == "closureStep" })
    }

    @Test
    fun `core methods and repeated metadata names do not duplicate signatures`() {
        val missingMethod = createStep("methodMissing", positionals = listOf("name", "value"))
        val steps = mapOf(
            "pipeline" to createStep("pipeline", hasBody = true),
            "methodMissing" to missingMethod.copy(
                namedParams = missingMethod.namedParams.mapValues { (name, param) ->
                    if (name == "value") param.copy(type = "Object") else param
                },
            ),
            "propertyMissing" to createStep("propertyMissing", positionals = listOf("name")),
            "first" to createStep("repeated"),
            "second" to createStep("repeated"),
        )
        val metadata = MergedJenkinsMetadata("test", steps, emptyMap(), emptyMap(), emptyMap())
        val source = JenkinsStubGenerator().render(metadata).getValue("org.jenkinsci.plugins.workflow.cps.CpsScript")
        val unit = CompilationUnit()
        unit.addSource("CpsScript.groovy", source)
        unit.compile(Phases.CLASS_GENERATION)
        val script = unit.ast.classes.single { it.name == "org.jenkinsci.plugins.workflow.cps.CpsScript" }
        assertEquals(3, script.methods.count { it.name == "pipeline" })
        assertEquals(2, script.methods.count { it.name == "methodMissing" })
        assertEquals(2, script.methods.count { it.name == "propertyMissing" })
        assertEquals(1, script.methods.count { it.name == "repeated" })
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
        assertTrue(content.contains("def 'simple'(Map args) {}"), "Missing simple(Map)")

        // Verify "withBody" (map, map+body, body-only)
        assertTrue(content.contains("def 'withBody'(Map args) {}"), "Missing withBody(Map)")
        assertTrue(content.contains("def 'withBody'(Map args, Closure body) {}"), "Missing withBody(Map, Closure)")
        assertTrue(content.contains("def 'withBody'(Closure body) {}"), "Missing withBody(Closure)")

        // Verify "withPositional" (map, single param)
        assertTrue(content.contains("def 'withPositional'(Map args) {}"), "Missing withPositional(Map)")
        assertTrue(content.contains("def 'withPositional'(String arg0) {}"), "Missing withPositional(String)")

        // Verify "complex" (map, map+body, body-only, positional, positional+body)
        assertTrue(content.contains("def 'complex'(Map args) {}"), "Missing complex(Map)")
        assertTrue(content.contains("def 'complex'(Map args, Closure body) {}"), "Missing complex(Map, Closure)")
        assertTrue(content.contains("def 'complex'(Closure body) {}"), "Missing complex(Closure)")
        assertTrue(content.contains("def 'complex'(String arg0) {}"), "Missing complex(String)")
        assertTrue(
            content.contains("def 'complex'(String arg0, Closure body) {}"),
            "Missing complex(String, Closure)",
        )
    }

    private fun scanConstructorFixtures(): Map<String, JenkinsStepMetadata> {
        val classes = tempDir.resolve("classes")
        Files.createDirectories(classes)
        val sourcePaths = constructorFixtureSources().map { (name, text) ->
            tempDir.resolve(name).also { path ->
                Files.createDirectories(path.parent)
                Files.writeString(path, text)
            }
        }
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        val status = compiler.run(
            null,
            null,
            null,
            "-parameters",
            "-d",
            classes.toString(),
            *sourcePaths.map { it.toString() }.toTypedArray(),
        )
        assertEquals(0, status, "Fixture Java compilation failed")

        return JenkinsClasspathScanner().scan(listOf(classes)).steps
    }

    private fun constructorFixtureSources(): Map<String, String> = mapOf(
        "org/jenkinsci/plugins/workflow/steps/Step.java" to
            "package org.jenkinsci.plugins.workflow.steps; public class Step {}",
        "org/jenkinsci/plugins/workflow/steps/StepDescriptor.java" to
            "package org.jenkinsci.plugins.workflow.steps; public class StepDescriptor {}",
        "org/kohsuke/stapler/DataBoundConstructor.java" to """
            package org.kohsuke.stapler;
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            public @interface DataBoundConstructor {}
        """.trimIndent(),
        "example/KeywordStep.java" to """
            package example;
            import org.jenkinsci.plugins.workflow.steps.*;
            import org.kohsuke.stapler.DataBoundConstructor;
            public class KeywordStep extends Step {
                @DataBoundConstructor public KeywordStep(String in) {}
                public static class DescriptorImpl extends StepDescriptor {}
            }
        """.trimIndent(),
        "example/BodyStep.java" to """
            package example;
            import org.jenkinsci.plugins.workflow.steps.*;
            import org.kohsuke.stapler.DataBoundConstructor;
            public class BodyStep extends Step {
                @DataBoundConstructor public BodyStep(String body) {}
                public static class DescriptorImpl extends StepDescriptor {}
            }
        """.trimIndent(),
    )

    private fun scannedStep(step: JenkinsStepMetadata): MergedStepMetadata = createStep(step.name).copy(
        positionalParams = step.positionalParams,
        namedParams = step.parameters.mapValues { (name, parameter) ->
            MergedParameter(
                name = name,
                type = parameter.type,
                defaultValue = parameter.default,
                description = parameter.documentation,
                required = parameter.required,
                validValues = null,
                examples = emptyList(),
            )
        },
    )

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

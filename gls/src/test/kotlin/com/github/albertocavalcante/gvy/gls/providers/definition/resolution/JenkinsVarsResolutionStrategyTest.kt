package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.groovyjenkins.GlobalVariable
import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.gvy.gls.project.JenkinsCapabilities
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionResolver
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files

class JenkinsVarsResolutionStrategyTest {

    @Test
    fun `Jenkins vars strategy resolves method call to vars file`() {
        val varsDir = Files.createTempDirectory("jenkins-vars-test")
        val varsFile = Files.createFile(varsDir.resolve("buildPlugin.groovy"))

        val jenkinsCapabilities = mockk<JenkinsCapabilities>()
        every { jenkinsCapabilities.getGlobalVariables() } returns listOf(
            GlobalVariable(name = "buildPlugin", path = varsFile),
        )

        val methodCall = MethodCallExpression(
            VariableExpression("this"),
            "buildPlugin",
            ArgumentListExpression(),
        )

        val strategy = JenkinsVarsResolutionStrategy(jenkinsCapabilities)
        val context = ResolutionContext(
            targetNode = methodCall,
            documentUri = URI.create("file:///workspace/Jenkinsfile"),
            position = Position(0, 0),
        )

        val result = runBlocking { strategy.resolve(context) }
        result.fold(
            ifLeft = { error ->
                throw AssertionError("Expected Right, got Left: ${error.source} - ${error.reason}")
            },
            ifRight = { definition ->
                assertTrue(definition is DefinitionResolver.DefinitionResult.Source)
                val source = definition as DefinitionResolver.DefinitionResult.Source
                assertEquals(varsFile.toUri(), source.uri)
                assertTrue(source.node is ClassNode)
                val node = source.node as ClassNode
                assertEquals(1, node.lineNumber)
                assertEquals(1, node.columnNumber)
            },
        )
    }

    @Test
    fun `Jenkins vars strategy does not treat ConstantExpression as method name`() {
        val jenkinsCapabilities = mockk<JenkinsCapabilities>(relaxed = true)
        val strategy = JenkinsVarsResolutionStrategy(jenkinsCapabilities)
        val context = ResolutionContext(
            targetNode = ConstantExpression("buildPlugin"),
            documentUri = URI.create("file:///workspace/Jenkinsfile"),
            position = Position(0, 0),
        )

        val result = runBlocking { strategy.resolve(context) }
        result.fold(
            ifLeft = { error ->
                assertEquals("JenkinsVars", error.source)
            },
            ifRight = { definition ->
                throw AssertionError("Expected Left, got Right: $definition")
            },
        )
    }

    @Test
    fun `Jenkins vars strategy resolves to def call line number`() {
        val varsDir = Files.createTempDirectory("jenkins-vars-line-test")
        val varsFile = varsDir.resolve("buildPlugin.groovy")

        // Write a vars file with def call on line 5
        Files.writeString(
            varsFile,
            """
            #!/usr/bin/env groovy
            /**
             * Documentation
             */
            def call(Map params = [:]) {
                echo "Building..."
            }
            """.trimIndent(),
        )

        val jenkinsCapabilities = mockk<JenkinsCapabilities>()
        every { jenkinsCapabilities.getGlobalVariables() } returns listOf(
            GlobalVariable(name = "buildPlugin", path = varsFile, documentation = "", callLineNumber = 5),
        )

        val methodCall = MethodCallExpression(
            VariableExpression("this"),
            "buildPlugin",
            ArgumentListExpression(),
        )

        val strategy = JenkinsVarsResolutionStrategy(jenkinsCapabilities)
        val context = ResolutionContext(
            targetNode = methodCall,
            documentUri = URI.create("file:///workspace/Jenkinsfile"),
            position = Position(0, 0),
        )

        val result = runBlocking { strategy.resolve(context) }
        result.fold(
            ifLeft = { error ->
                throw AssertionError("Expected Right, got Left: ${error.source} - ${error.reason}")
            },
            ifRight = { definition ->
                assertTrue(definition is DefinitionResolver.DefinitionResult.Source)
                val source = definition as DefinitionResolver.DefinitionResult.Source
                assertEquals(varsFile.toUri(), source.uri)
                assertTrue(source.node is ClassNode)
                val node = source.node as ClassNode
                // Should navigate to line 5 where def call is, not line 1
                assertEquals(5, node.lineNumber, "Should navigate to def call line, not line 1")
            },
        )
    }

    @Test
    fun `Jenkins vars strategy resolves VariableExpression to vars file`() {
        val varsDir = Files.createTempDirectory("jenkins-vars-variable-test")
        val varsFile = Files.createFile(varsDir.resolve("infra.groovy"))

        val jenkinsCapabilities = mockk<JenkinsCapabilities>()
        every { jenkinsCapabilities.getGlobalVariables() } returns listOf(
            GlobalVariable(name = "infra", path = varsFile),
        )

        // Simulate clicking on 'infra' in 'infra.checkoutSCM()'
        val variableExpr = VariableExpression("infra")

        val strategy = JenkinsVarsResolutionStrategy(jenkinsCapabilities)
        val context = ResolutionContext(
            targetNode = variableExpr,
            documentUri = URI.create("file:///workspace/Jenkinsfile"),
            position = Position(0, 0),
        )

        val result = runBlocking { strategy.resolve(context) }
        result.fold(
            ifLeft = { error ->
                throw AssertionError("Expected Right, got Left: ${error.source} - ${error.reason}")
            },
            ifRight = { definition ->
                assertTrue(definition is DefinitionResolver.DefinitionResult.Source)
                val source = definition as DefinitionResolver.DefinitionResult.Source
                assertEquals(varsFile.toUri(), source.uri)
                assertTrue(source.node is ClassNode)
            },
        )
    }

    @Test
    fun `Jenkins vars strategy does not resolve non-Jenkins VariableExpression`() {
        val varsDir = Files.createTempDirectory("jenkins-vars-nonmatch-test")
        val varsFile = Files.createFile(varsDir.resolve("infra.groovy"))

        val jenkinsCapabilities = mockk<JenkinsCapabilities>()
        every { jenkinsCapabilities.getGlobalVariables() } returns listOf(
            GlobalVariable(name = "infra", path = varsFile),
        )

        // 'someLocalVar' is not a Jenkins global variable
        val variableExpr = VariableExpression("someLocalVar")

        val strategy = JenkinsVarsResolutionStrategy(jenkinsCapabilities)
        val context = ResolutionContext(
            targetNode = variableExpr,
            documentUri = URI.create("file:///workspace/Jenkinsfile"),
            position = Position(0, 0),
        )

        val result = runBlocking { strategy.resolve(context) }
        result.fold(
            ifLeft = { error ->
                assertEquals("JenkinsVars", error.source)
            },
            ifRight = { definition ->
                throw AssertionError("Expected Left, got Right: $definition")
            },
        )
    }
}

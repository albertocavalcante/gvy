package com.github.albertocavalcante.groovylsp.providers.definition.resolution

import com.github.albertocavalcante.groovyjenkins.GlobalVariable
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionResolver
import com.github.albertocavalcante.groovyparser.ast.types.Position
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

        val compilationService = mockk<GroovyCompilationService>()
        every { compilationService.getJenkinsGlobalVariables() } returns listOf(
            GlobalVariable(name = "buildPlugin", path = varsFile),
        )

        val methodCall = MethodCallExpression(
            VariableExpression("this"),
            "buildPlugin",
            ArgumentListExpression(),
        )

        val strategy = JenkinsVarsResolutionStrategy(compilationService)
        val context = ResolutionContext(
            targetNode = methodCall,
            documentUri = URI.create("file:///workspace/Jenkinsfile"),
            position = Position(0, 0),
        )

        val result = runBlocking { strategy.resolve(context) }
        result.fold(
            ifLeft = { error ->
                throw AssertionError("Expected Right, got Left: ${error.strategy} - ${error.reason}")
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
        val compilationService = mockk<GroovyCompilationService>(relaxed = true)
        val strategy = JenkinsVarsResolutionStrategy(compilationService)
        val context = ResolutionContext(
            targetNode = ConstantExpression("buildPlugin"),
            documentUri = URI.create("file:///workspace/Jenkinsfile"),
            position = Position(0, 0),
        )

        val result = runBlocking { strategy.resolve(context) }
        result.fold(
            ifLeft = { error ->
                assertEquals("JenkinsVars", error.strategy)
            },
            ifRight = { definition ->
                throw AssertionError("Expected Left, got Right: $definition")
            },
        )
    }
}

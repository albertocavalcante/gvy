package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.gvy.gls.config.GroovyMode
import com.github.albertocavalcante.gvy.gls.config.ModeResolver
import com.github.albertocavalcante.gvy.gls.project.JenkinsCapabilities
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionResolver
import com.github.albertocavalcante.gvy.jenkins.GlobalVariable
import groovy.lang.Script
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.DynamicVariable
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.EmptyExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.syntax.Token
import org.codehaus.groovy.syntax.Types
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.net.URI
import java.nio.file.Files

class JenkinsVarsResolutionStrategyTest {

    @Test
    fun `Jenkins vars strategy resolves method call to vars file`() {
        val varsDir = Files.createTempDirectory("jenkins-vars-test")
        val varsFile = Files.createFile(varsDir.resolve("buildPlugin.groovy"))

        val jenkinsCapabilities = mockk<JenkinsCapabilities>()
        every { jenkinsCapabilities.isJenkinsFile(any()) } returns true
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
        every { jenkinsCapabilities.isJenkinsFile(any()) } returns true
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
        every { jenkinsCapabilities.isJenkinsFile(any()) } returns true
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
        every { jenkinsCapabilities.isJenkinsFile(any()) } returns true
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

    @Test
    fun `Jenkins vars strategy skips method call on a typed receiver`() {
        val strategy = JenkinsVarsResolutionStrategy(capabilitiesWith("deploy"))
        val receiver = VariableExpression(Parameter(ClassHelper.STRING_TYPE, "helper"))
        val call = MethodCallExpression(receiver, "deploy", ArgumentListExpression())

        assertNotResolved(strategy, call, JENKINSFILE)
    }

    @Test
    fun `Jenkins vars strategy resolves call on a dynamically typed receiver outside Jenkins mode`() {
        val strategy = JenkinsVarsResolutionStrategy(capabilitiesWith("deploy", jenkinsFile = false))
        val receiver = VariableExpression(VariableExpression("steps"))
        val call = MethodCallExpression(receiver, "deploy", ArgumentListExpression())

        assertResolved(strategy, call, URI.create("file:///workspace/src/org/x/Foo.groovy"))
    }

    @Test
    fun `Jenkins vars strategy resolves call on a Script typed field receiver`() {
        val owner = ClassNode("org.x.Foo", 1, ClassHelper.OBJECT_TYPE)
        val field = FieldNode("script", 1, ClassHelper.make(Script::class.java), owner, null)
        val strategy = JenkinsVarsResolutionStrategy(capabilitiesWith("deploy", jenkinsFile = false))
        val call = MethodCallExpression(VariableExpression(field), "deploy", ArgumentListExpression())

        assertResolved(strategy, call, URI.create("file:///workspace/src/org/x/Foo.groovy"))
    }

    @Test
    fun `Jenkins vars strategy skips implicit call inside a non-script class`() {
        val helperClass = ClassNode("H", 1, ClassHelper.OBJECT_TYPE)
        val method = MethodNode(
            "go",
            1,
            ClassHelper.OBJECT_TYPE,
            Parameter.EMPTY_ARRAY,
            ClassNode.EMPTY_ARRAY,
            BlockStatement(),
        )
        helperClass.addMethod(method)
        val call = MethodCallExpression(VariableExpression("this"), "deploy", ArgumentListExpression())
        val astModel = mockk<GroovyAstModel> {
            every { getParent(call) } returns method
            every { getParent(method) } returns helperClass
            every { getParent(helperClass) } returns null
        }
        val strategy = JenkinsVarsResolutionStrategy(capabilitiesWith("deploy"), astModel = astModel)

        assertNotResolved(strategy, call, JENKINSFILE)
    }

    @Test
    fun `Jenkins vars strategy skips implicit call inside a static script method`() {
        val method = MethodNode(
            "f",
            Modifier.PUBLIC or Modifier.STATIC,
            ClassHelper.OBJECT_TYPE,
            Parameter.EMPTY_ARRAY,
            ClassNode.EMPTY_ARRAY,
            BlockStatement(),
        )
        scriptClass().addMethod(method)
        val call = MethodCallExpression(VariableExpression("this"), "deploy", ArgumentListExpression())
        val astModel = mockk<GroovyAstModel> {
            every { getParent(call) } returns method
            every { getParent(method) } returns null
        }
        val strategy = JenkinsVarsResolutionStrategy(capabilitiesWith("deploy"), astModel = astModel)

        assertNotResolved(strategy, call, JENKINSFILE)
    }

    @Test
    fun `Jenkins vars strategy skips method call on a property receiver`() {
        val strategy = JenkinsVarsResolutionStrategy(capabilitiesWith("deploy"))
        val receiver = PropertyExpression(VariableExpression("this"), "steps")
        val call = MethodCallExpression(receiver, "deploy", ArgumentListExpression())

        assertNotResolved(strategy, call, JENKINSFILE)
    }

    @Test
    fun `Jenkins vars strategy skips variable bound to a local declaration`() {
        val strategy = JenkinsVarsResolutionStrategy(capabilitiesWith("deploy"))
        val declared = VariableExpression("deploy")
        val reference = VariableExpression(declared)

        assertNotResolved(strategy, reference, JENKINSFILE)
    }

    @Test
    fun `Jenkins vars strategy skips variable bound to a parameter`() {
        val strategy = JenkinsVarsResolutionStrategy(capabilitiesWith("deploy"))
        val reference = VariableExpression(Parameter(ClassNode(Any::class.java), "deploy"))

        assertNotResolved(strategy, reference, JENKINSFILE)
    }

    @Test
    fun `Jenkins vars strategy resolves dynamic variable reference`() {
        val strategy = JenkinsVarsResolutionStrategy(capabilitiesWith("deploy"))
        val reference = VariableExpression(DynamicVariable("deploy", false))

        assertResolved(strategy, reference, JENKINSFILE)
    }

    @Test
    fun `Jenkins vars strategy skips files outside Jenkins mode`() {
        val strategy = JenkinsVarsResolutionStrategy(capabilitiesWith("deploy", jenkinsFile = false))
        val call = MethodCallExpression(VariableExpression("this"), "deploy", ArgumentListExpression())

        assertNotResolved(strategy, call, URI.create("file:///workspace/src/org/x/Foo.groovy"))
    }

    @Test
    fun `Jenkins vars strategy honors explicit Jenkins mode for any file name`() {
        val capabilities = capabilitiesWith("deploy", jenkinsFile = false)
        val modeResolver = ModeResolver(GroovyMode.JENKINS, capabilities)
        val strategy = JenkinsVarsResolutionStrategy(capabilities, modeResolver::isJenkinsModeEnabled)
        val call = MethodCallExpression(VariableExpression("this"), "deploy", ArgumentListExpression())

        assertResolved(strategy, call, URI.create("file:///workspace/pipelines/release.groovy"))
    }

    @Test
    fun `Jenkins vars strategy honors explicit Groovy mode for a Jenkinsfile`() {
        val capabilities = capabilitiesWith("deploy")
        val modeResolver = ModeResolver(GroovyMode.GROOVY, capabilities)
        val strategy = JenkinsVarsResolutionStrategy(capabilities, modeResolver::isJenkinsModeEnabled)
        val call = MethodCallExpression(VariableExpression("this"), "deploy", ArgumentListExpression())

        assertNotResolved(strategy, call, JENKINSFILE)
    }

    @Test
    fun `Jenkins vars strategy skips call shadowed by a method in the enclosing class`() {
        val script = scriptClass().apply {
            addMethod(
                "deploy",
                1,
                ClassHelper.OBJECT_TYPE,
                Parameter.EMPTY_ARRAY,
                ClassNode.EMPTY_ARRAY,
                BlockStatement(),
            )
        }
        val call = MethodCallExpression(VariableExpression("this"), "deploy", ArgumentListExpression())
        val astModel = mockk<GroovyAstModel> {
            every { getParent(call) } returns script
            every { getParent(script) } returns null
        }
        val strategy = JenkinsVarsResolutionStrategy(capabilitiesWith("deploy"), astModel = astModel)

        assertNotResolved(strategy, call, JENKINSFILE)
    }

    @Test
    fun `Jenkins vars strategy falls back to the script class when no parent is tracked`() {
        val script = scriptClass().apply {
            addField("deploy", 1, ClassHelper.OBJECT_TYPE, null)
        }
        val reference = VariableExpression("deploy")
        val astModel = mockk<GroovyAstModel> {
            every { getParent(reference) } returns null
            every { getAllClassNodes() } returns listOf(script)
            // Untracked script classes (no top-level statements) have no recorded URI.
            every { getUri(script) } returns null
        }
        val strategy = JenkinsVarsResolutionStrategy(capabilitiesWith("deploy"), astModel = astModel)

        assertNotResolved(strategy, reference, JENKINSFILE)
    }

    @Test
    fun `Jenkins vars strategy finds the script class through the enclosing method`() {
        val helper = MethodNode(
            "deploy",
            1,
            ClassHelper.OBJECT_TYPE,
            Parameter.EMPTY_ARRAY,
            ClassNode.EMPTY_ARRAY,
            BlockStatement(),
        )
        val callBody = MethodNode(
            "call",
            1,
            ClassHelper.OBJECT_TYPE,
            Parameter.EMPTY_ARRAY,
            ClassNode.EMPTY_ARRAY,
            BlockStatement(),
        )
        scriptClass().apply {
            addMethod(helper)
            addMethod(callBody)
        }
        val call = MethodCallExpression(VariableExpression("this"), "deploy", ArgumentListExpression())
        // The script class is untracked, so the parent chain ends at the enclosing method.
        val astModel = mockk<GroovyAstModel> {
            every { getParent(call) } returns callBody
            every { getParent(callBody) } returns null
        }
        val strategy = JenkinsVarsResolutionStrategy(capabilitiesWith("deploy"), astModel = astModel)

        assertNotResolved(strategy, call, URI.create("file:///workspace/vars/release.groovy"))
    }

    @Test
    fun `Jenkins vars strategy resolves variable reference despite a same-named method`() {
        val script = scriptClass().apply {
            addMethod(
                "deploy",
                1,
                ClassHelper.OBJECT_TYPE,
                Parameter.EMPTY_ARRAY,
                ClassNode.EMPTY_ARRAY,
                BlockStatement(),
            )
        }
        val reference = VariableExpression(DynamicVariable("deploy", false))
        val astModel = mockk<GroovyAstModel> {
            every { getParent(reference) } returns script
            every { getParent(script) } returns null
        }
        val strategy = JenkinsVarsResolutionStrategy(capabilitiesWith("deploy"), astModel = astModel)

        assertResolved(strategy, reference, JENKINSFILE)
    }

    @Test
    fun `Jenkins vars strategy resolves call when the enclosing class declares other members only`() {
        val script = scriptClass().apply {
            addMethod(
                "helper",
                1,
                ClassHelper.OBJECT_TYPE,
                Parameter.EMPTY_ARRAY,
                ClassNode.EMPTY_ARRAY,
                BlockStatement(),
            )
        }
        val call = MethodCallExpression(VariableExpression("this"), "deploy", ArgumentListExpression())
        val astModel = mockk<GroovyAstModel> {
            every { getParent(call) } returns script
            every { getParent(script) } returns null
        }
        val strategy = JenkinsVarsResolutionStrategy(capabilitiesWith("deploy"), astModel = astModel)

        assertResolved(strategy, call, JENKINSFILE)
    }

    @Test
    fun `Jenkins vars strategy skips call on a def local initialized with a map literal`() {
        val call = callOnDefLocal("deploy", MapExpression())

        assertNotResolved(call.strategy, call.call, JENKINSFILE)
    }

    @Test
    fun `Jenkins vars strategy skips call on a def local initialized with a constructor call`() {
        val helperType = ClassNode("Helper", 1, ClassHelper.OBJECT_TYPE)
        val call = callOnDefLocal("deploy", ConstructorCallExpression(helperType, ArgumentListExpression()))

        assertNotResolved(call.strategy, call.call, JENKINSFILE)
    }

    @Test
    fun `Jenkins vars strategy skips call on a def local initialized with a string literal`() {
        val call = callOnDefLocal("deploy", ConstantExpression("abc"))

        assertNotResolved(call.strategy, call.call, JENKINSFILE)
    }

    @Test
    fun `Jenkins vars strategy resolves call on a def local initialized from another variable`() {
        val call = callOnDefLocal("deploy", VariableExpression(DynamicVariable("script", false)))

        assertResolved(call.strategy, call.call, JENKINSFILE)
    }

    @Test
    fun `Jenkins vars strategy resolves call on a def local without an initializer`() {
        val call = callOnDefLocal("deploy", EmptyExpression.INSTANCE)

        assertResolved(call.strategy, call.call, JENKINSFILE)
    }

    @Test
    fun `Jenkins vars strategy honors explicit Groovy mode for a dynamically typed receiver`() {
        val capabilities = capabilitiesWith("deploy", jenkinsFile = false)
        val modeResolver = ModeResolver(GroovyMode.GROOVY, capabilities)
        val strategy = JenkinsVarsResolutionStrategy(
            capabilities,
            modeResolver::isJenkinsModeEnabled,
            isGroovyOnlyMode = modeResolver::isGroovyOnlyMode,
        )
        val owner = ClassNode("org.x.Foo", 1, ClassHelper.OBJECT_TYPE)
        val field = FieldNode("steps", 1, ClassHelper.OBJECT_TYPE, owner, null)
        val call = MethodCallExpression(VariableExpression(field), "deploy", ArgumentListExpression())

        assertNotResolved(strategy, call, URI.create("file:///workspace/src/org/x/Foo.groovy"))
    }

    private class CallOnLocal(val strategy: JenkinsVarsResolutionStrategy, val call: MethodCallExpression)

    /** `def receiver = [initializer]; receiver.[method]()` with the declaration tracked as the local's parent. */
    private fun callOnDefLocal(method: String, initializer: Expression): CallOnLocal {
        val declared = VariableExpression("receiver")
        val declaration = DeclarationExpression(declared, Token.newSymbol(Types.ASSIGN, -1, -1), initializer)
        val call = MethodCallExpression(VariableExpression(declared), method, ArgumentListExpression())
        val astModel = mockk<GroovyAstModel> {
            every { getParent(declared) } returns declaration
        }
        return CallOnLocal(JenkinsVarsResolutionStrategy(capabilitiesWith(method), astModel = astModel), call)
    }

    private fun scriptClass(): ClassNode = ClassNode("Jenkinsfile", 1, ClassHelper.make(Script::class.java)).apply {
        isScript = true
    }

    private fun capabilitiesWith(name: String, jenkinsFile: Boolean = true): JenkinsCapabilities {
        val varsFile = Files.createFile(Files.createTempDirectory("jenkins-vars-scope-test").resolve("$name.groovy"))
        return mockk<JenkinsCapabilities> {
            every { isJenkinsFile(any()) } returns jenkinsFile
            every { getGlobalVariables() } returns listOf(GlobalVariable(name = name, path = varsFile))
        }
    }

    private fun resolve(strategy: JenkinsVarsResolutionStrategy, node: ASTNode, uri: URI): ResolutionResult =
        runBlocking {
            strategy.resolve(ResolutionContext(targetNode = node, documentUri = uri, position = Position(0, 0)))
        }

    private fun assertNotResolved(strategy: JenkinsVarsResolutionStrategy, node: ASTNode, uri: URI) {
        resolve(strategy, node, uri).fold(
            ifLeft = { error -> assertEquals("JenkinsVars", error.source) },
            ifRight = { definition -> throw AssertionError("Expected Left, got Right: $definition") },
        )
    }

    private fun assertResolved(strategy: JenkinsVarsResolutionStrategy, node: ASTNode, uri: URI) {
        resolve(strategy, node, uri).fold(
            ifLeft = { error -> throw AssertionError("Expected Right, got Left: ${error.source} - ${error.reason}") },
            ifRight = { definition -> assertTrue(definition is DefinitionResolver.DefinitionResult.Source) },
        )
    }

    private companion object {
        val JENKINSFILE: URI = URI.create("file:///workspace/Jenkinsfile")
    }
}

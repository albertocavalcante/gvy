package com.github.albertocavalcante.gvy.gls

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hover for Jenkins step parameters and global variables: they used to fall through to the generic
 * Groovy hover ("String literal", "def env") although the bundled metadata documents them (#1245).
 */
class JenkinsParameterHoverTest {

    private var serverHandle: TestLanguageServerHandle? = null

    @BeforeEach
    fun setup() {
        val runner = TestLanguageServerRunner()
        serverHandle = runner.startInMemoryServer()

        // Initialize the server with a workspace folder so Jenkins file detection works
        val initParams = InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder("file:///tmp/jenkins-test", "jenkins-test"))
            initializationOptions = mapOf("groovy.languageServer.engine" to "native")
        }
        serverHandle!!.server.initialize(initParams).get()
        serverHandle!!.server.initialized(InitializedParams())

        // Wait for server to be ready and quiescent (background tasks finished)
        serverHandle!!.client.awaitQuiescentStatus()
    }

    @AfterEach
    fun cleanup() {
        serverHandle?.stop()
    }

    private var documents = 0

    private suspend fun hoverAt(content: String, line: Int, character: Int, name: String = "Jenkinsfile"): Hover? {
        val uri = "file:///tmp/jenkins-test/hover${documents++}/$name"
        openDocument(uri, content)
        return requestHoverAt(uri, Position(line, character))
    }

    private suspend fun hoverText(content: String, line: Int, character: Int, name: String = "Jenkinsfile"): String? =
        hoverAt(content, line, character, name)?.contents?.right?.value

    /** True when [text] is this strategy's parameter hover. */
    private fun isParameterHover(text: String?) = text?.contains("Parameter `") == true

    /** True when [text] is this strategy's global-variable hover. */
    private fun isGlobalHover(text: String?) = text?.contains("Jenkins Global Variable") == true

    @Test
    fun `hover on a step's named argument shows the parameter documentation`(): Unit = runBlocking {
        val line = "    sh(script: 'make', returnStdout: true)"
        val text = hoverText("node {\n$line\n}", line = 1, character = line.indexOf("returnStdout") + 2)

        assertNotNull(text)
        assertTrue(text.contains("returnStdout") && text.contains("boolean"), "parameter name and type: $text")
        assertTrue(text.contains("return stdout"), "parameter description: $text")
        assertTrue(text.contains("`sh`"), "names the step: $text")
    }

    @Test
    fun `hover on a directive entry argument uses the directive's parameter`(): Unit = runBlocking {
        val line = "        string(name: 'X', trim: true)"
        val code = "pipeline {\n    agent any\n    parameters {\n$line\n    }\n}"
        val text = hoverText(code, line = 3, character = line.indexOf("trim") + 1)

        assertNotNull(text)
        assertTrue(text.contains("trim") && text.contains("whitespace"), "string parameter's trim: $text")
    }

    @Test
    fun `hover on a credentials binding argument uses the step's parameter`(): Unit = runBlocking {
        val line = "    withCredentials([string(credentialsId: 'token', variable: 'T')]) {"
        val text = hoverText("node {\n$line\n    }\n}", line = 1, character = line.indexOf("credentialsId") + 2)

        assertNotNull(text)
        assertTrue(text.contains("credentialsId") && text.contains("Secret text"), "credentials binding: $text")
    }

    @Test
    fun `hover on a Jenkins global variable shows its documentation`(): Unit = runBlocking {
        val line = "    echo env.BRANCH_NAME"
        val text = hoverText("node {\n$line\n}", line = 1, character = line.indexOf("env") + 1)

        assertNotNull(text)
        assertTrue(text.contains("env") && text.contains("Environment variables"), "env documentation: $text")
    }

    @Test
    fun `the parameter hover range is exactly the key`(): Unit = runBlocking {
        val line = "    sh(script: 'make', returnStdout: true)"
        val start = line.indexOf("returnStdout")
        val hover = hoverAt("node {\n$line\n}", line = 1, character = start + 2)

        val range = assertNotNull(hover?.range)
        assertEquals(Range(Position(1, start), Position(1, start + "returnStdout".length)), range)
    }

    @Test
    fun `plain Groovy files get no Jenkins parameter or global hover`(): Unit = runBlocking {
        val line = "sh(script: 'make', returnStdout: true)"
        val param = hoverText(line, line = 0, character = line.indexOf("returnStdout") + 2, name = "Build.groovy")
        val global = hoverText("println env", line = 0, character = 9, name = "Build.groovy")

        assertTrue(!isParameterHover(param), "plain Groovy: $param")
        assertTrue(!isGlobalHover(global), "plain Groovy: $global")
    }

    @Test
    fun `a map literal in a step's closure body is not the step's arguments`(): Unit = runBlocking {
        val line = "        def m = [time: 2]"
        val text = hoverText(
            "node {\n    timeout(time: 1) {\n$line\n    }\n}",
            line = 2,
            character =
            line.indexOf("time") + 1,
        )

        assertTrue(!isParameterHover(text), "closure-body map literal: $text")
    }

    @Test
    fun `a map literal outside any call is not a parameter`(): Unit = runBlocking {
        val line = "    def m = [returnStdout: true]"
        val text = hoverText("node {\n$line\n}", line = 1, character = line.indexOf("returnStdout") + 2)

        assertTrue(!isParameterHover(text), "standalone map literal: $text")
    }

    @Test
    fun `a key of a nested map value is not the outer step's parameter`(): Unit = runBlocking {
        val line = "    sh(label: [script: 'inner'], script: 'make')"
        val text = hoverText("node {\n$line\n}", line = 1, character = line.indexOf("script") + 2)

        assertTrue(!isParameterHover(text), "nested map key picked up sh's script parameter: $text")
    }

    @Test
    fun `a method parameter named env is not the global`(): Unit = runBlocking {
        val line = "    echo env"
        val text = hoverText(
            "def report(env) {\n$line\n}\nnode { report('x') }",
            line = 1,
            character =
            line.indexOf("env") + 1,
        )

        assertTrue(!isGlobalHover(text), "parameter env: $text")
    }

    @Test
    fun `a map that is a call's receiver is not its arguments`(): Unit = runBlocking {
        val line = "    [returnStdout: true].each { echo it.key }"
        val text = hoverText("node {\n$line\n}", line = 1, character = line.indexOf("returnStdout") + 2)

        assertTrue(!isParameterHover(text), "receiver map key: $text")
    }

    @Test
    fun `hover on a declarative option shows the option`(): Unit = runBlocking {
        val line = "        timeout(time: 1, unit: 'HOURS')"
        val code = "pipeline {\n    agent any\n    options {\n$line\n    }\n}"
        val text = hoverText(code, line = 3, character = line.indexOf("timeout") + 2)

        assertNotNull(text)
        assertTrue(text.contains("Declarative option `timeout`"), "option hover: $text")
    }

    @Test
    fun `a step named like an option keeps the step hover in steps`(): Unit = runBlocking {
        val line = "                timeout(time: 1) { sh 'make' }"
        val code = "pipeline {\n    agent any\n    stages {\n        stage('B') {\n            steps {\n" +
            "$line\n            }\n        }\n    }\n}"
        val text = hoverText(code, line = 5, character = line.indexOf("timeout") + 2)

        assertNotNull(text)
        assertTrue(text.contains("Jenkins Step: `timeout`"), "step hover: $text")
    }

    @Test
    fun `hover on declarative sections and directives shows their documentation`(): Unit = runBlocking {
        val lines = listOf("pipeline {", "    agent any", "    options {", "    }", "    stages {", "    }", "}")
        val code = lines.joinToString("\n")
        val agent = hoverText(code, line = 1, character = 6)
        val options = hoverText(code, line = 2, character = 6)
        val stages = hoverText(code, line = 4, character = 6)

        assertTrue(agent?.contains("Specifies where") == true, "agent section: $agent")
        assertTrue(options?.contains("Configure pipeline or stage options") == true, "options directive: $options")
        assertTrue(stages?.contains("Declarative section `stages`") == true, "stages section: $stages")
    }

    @Test
    fun `a section name used where the section is not allowed keeps the step hover`(): Unit = runBlocking {
        val line = "                stage('inner') { echo 'x' }"
        val code = "pipeline {\n    agent any\n    stages {\n        stage('B') {\n            steps {\n" +
            "$line\n            }\n        }\n    }\n}"
        val text = hoverText(code, line = 5, character = line.indexOf("stage") + 2)

        assertTrue(text == null || !text.contains("Declarative section"), "stage inside steps: $text")
    }

    @Test
    fun `a declarative hover range covers the name, not the whole block`(): Unit = runBlocking {
        val code = "pipeline {\n    agent any\n    stages {\n    }\n}"
        val pipeline = hoverAt(code, line = 0, character = 2)
        val stages = hoverAt(code, line = 2, character = 6)

        assertTrue(pipeline?.contents?.right?.value?.contains("Declarative section `pipeline`") == true)
        assertEquals(Range(Position(0, 0), Position(0, 8)), pipeline?.range)
        assertEquals(Range(Position(2, 4), Position(2, 10)), stages?.range)
    }

    /** Guard: passes with or without the strategy (the ordinary hover never mentions the Jenkins env docs). */
    @Test
    fun `a local variable named like a global keeps the ordinary hover`(): Unit = runBlocking {
        val line = "    echo env"
        val text = hoverText("node {\n    def env = 'x'\n$line\n}", line = 2, character = line.indexOf("env") + 1)

        assertTrue(
            text == null || !text.contains("Environment variables available"),
            "local env is not Jenkins env: $text",
        )
    }

    private suspend fun openDocument(uri: String, content: String) {
        val textDoc = TextDocumentItem().apply {
            this.uri = uri
            languageId = "groovy"
            version = 1
            text = content
        }

        serverHandle!!.server.textDocumentService.didOpen(
            DidOpenTextDocumentParams().apply {
                textDocument = textDoc
            },
        )
    }

    private suspend fun requestHoverAt(uri: String, position: Position): Hover? {
        val params = HoverParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            this.position = position
        }

        return serverHandle!!.server.textDocumentService.hover(params).get()
    }
}

package com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.builtin

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.GroovyMode
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.CustomRulesProvider
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.RuleContext
import com.github.albertocavalcante.gvy.jenkins.JenkinsConfiguration
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the rule through [CustomRulesProvider] and a real [GroovyCompilationService], so the
 * AST, error state and Jenkins file detection are the ones the language server provides.
 */
class JenkinsPipelineStageRuleTest {

    private val jenkinsfile = URI.create("file:///workspace/Jenkinsfile")

    private fun analyze(
        code: String,
        uri: URI = jenkinsfile,
        configuration: ServerConfiguration? = null,
    ): List<Diagnostic> = runBlocking {
        val compilationService = GroovyCompilationService()
        configuration?.let { compilationService.updateServerConfiguration(it) }
        compilationService.compile(uri, code)
        CustomRulesProvider(listOf(JenkinsPipelineStageRule()), compilationService)
            .provideDiagnostics(uri, code)
            .toList()
    }

    private fun List<Diagnostic>.messages(): List<String> = map { it.message }

    @Test
    fun `declarative stage without steps is reported at the stage call`() {
        val code = """
            pipeline {
                agent any
                stages {
                    stage('Build') {
                        echo 'Building...'
                    }
                }
            }
        """.trimIndent()

        val diagnostics = analyze(code)

        assertEquals(1, diagnostics.size, diagnostics.messages().toString())
        val diagnostic = diagnostics.single()
        assertEquals(
            "Stage 'Build' is missing a 'steps', 'stages', 'parallel' or 'matrix' block",
            diagnostic.message,
        )
        assertEquals(DiagnosticSeverity.Warning, diagnostic.severity)
        assertEquals(Range(Position(3, 8), Position(3, 22)), diagnostic.range)
        assertEquals("stage('Build')", code.lines()[3].substring(8, 22))
    }

    @Test
    fun `declarative stage with steps is accepted`() {
        val code = """
            pipeline {
                agent any
                stages {
                    stage('Build') {
                        steps {
                            sh 'make build'
                        }
                    }
                }
            }
        """.trimIndent()

        assertEquals(emptyList(), analyze(code).messages())
    }

    @Test
    fun `scripted pipeline stages are never reported`() {
        val code = """
            node {
                stage('Build') {
                    sh 'make'
                }
                stage('Test') {
                    sh 'make test'
                }
            }
        """.trimIndent()

        assertEquals(emptyList(), analyze(code).messages())
    }

    @Test
    fun `scripted stages in vars helpers are never reported`() {
        val code = """
            def call(Map config = [:]) {
                stage('Test') {
                    sh 'make test'
                }
            }
        """.trimIndent()

        val diagnostics = analyze(code, URI.create("file:///workspace/vars/runTests.groovy"))

        assertEquals(emptyList(), diagnostics.messages())
    }

    @Test
    fun `declarative pipeline defined in a vars helper is checked`() {
        val code = """
            def call() {
                pipeline {
                    agent any
                    stages {
                        stage('Test') {
                            echo 'no steps'
                        }
                    }
                }
            }
        """.trimIndent()

        val diagnostics = analyze(code, URI.create("file:///workspace/vars/standardPipeline.groovy"))

        assertEquals(1, diagnostics.size, diagnostics.messages().toString())
        assertTrue(diagnostics.single().message.startsWith("Stage 'Test' is missing"))
        assertEquals(Range(Position(4, 12), Position(4, 25)), diagnostics.single().range)
    }

    @Test
    fun `stage with nested sequential stages is accepted and children are checked`() {
        val code = """
            pipeline {
                agent any
                stages {
                    stage('Outer') {
                        stages {
                            stage('Inner ok') {
                                steps { sh 'make' }
                            }
                            stage('Inner empty') {
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        val diagnostics = analyze(code)

        assertEquals(
            listOf("Stage 'Inner empty' is missing a 'steps', 'stages', 'parallel' or 'matrix' block"),
            diagnostics.messages(),
        )
    }

    @Test
    fun `stage with parallel stages is accepted`() {
        val code = """
            pipeline {
                agent none
                stages {
                    stage('Checks') {
                        parallel {
                            stage('Lint') {
                                steps { sh 'make lint' }
                            }
                            stage('Unit') {
                                steps { sh 'make test' }
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        assertEquals(emptyList(), analyze(code).messages())
    }

    @Test
    fun `stage with matrix is accepted`() {
        val code = """
            pipeline {
                agent none
                stages {
                    stage('Platforms') {
                        matrix {
                            axes {
                                axis {
                                    name 'PLATFORM'
                                    values 'linux', 'windows'
                                }
                            }
                            stages {
                                stage('Build') {
                                    steps { echo "Building on ${'$'}{PLATFORM}" }
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        assertEquals(emptyList(), analyze(code).messages())
    }

    @Test
    fun `script directly inside a declarative stage is reported`() {
        val code = """
            pipeline {
                agent any
                stages {
                    stage('Deploy') {
                        script {
                            echo 'Deploying...'
                        }
                    }
                }
            }
        """.trimIndent()

        val diagnostics = analyze(code)

        assertEquals(
            listOf(
                "Stage 'Deploy' is missing a 'steps', 'stages', 'parallel' or 'matrix' block",
                "'script' must be inside 'steps' (stage 'Deploy')",
            ),
            diagnostics.messages(),
        )
        assertEquals(Range(Position(4, 12), Position(4, 18)), diagnostics[1].range)
        assertEquals("script", code.lines()[4].substring(12, 18))
    }

    @Test
    fun `script inside steps is accepted`() {
        val code = """
            pipeline {
                agent any
                stages {
                    stage('Deploy') {
                        steps {
                            script {
                                echo 'Deploying...'
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        assertEquals(emptyList(), analyze(code).messages())
    }

    @Test
    fun `stage with more than one execution block is reported`() {
        val code = """
            pipeline {
                agent any
                stages {
                    stage('Mixed') {
                        steps { sh 'make' }
                        parallel {
                            stage('A') { steps { sh 'a' } }
                        }
                    }
                }
            }
        """.trimIndent()

        assertEquals(
            listOf("Stage 'Mixed' must contain only one of 'steps', 'stages', 'parallel' or 'matrix'"),
            analyze(code).messages(),
        )
    }

    @Test
    fun `Jenkinsfile variants matched by the default patterns are analyzed`() {
        val code = """
            pipeline {
                agent any
                stages {
                    stage('Release') {
                    }
                }
            }
        """.trimIndent()

        val diagnostics = analyze(code, URI.create("file:///workspace/Jenkinsfile.prod"))

        assertEquals(1, diagnostics.size, diagnostics.messages().toString())
    }

    @Test
    fun `explicit Jenkins mode analyzes any file`() {
        val code = """
            pipeline {
                agent any
                stages {
                    stage('Release') {
                    }
                }
            }
        """.trimIndent()

        val diagnostics = analyze(
            code,
            URI.create("file:///workspace/ci/release.groovy"),
            ServerConfiguration(groovyMode = GroovyMode.JENKINS),
        )

        assertEquals(1, diagnostics.size, diagnostics.messages().toString())
    }

    @Test
    fun `configured file patterns apply without a Jenkins workspace`() {
        val code = """
            pipeline {
                agent any
                stages {
                    stage('Release') {
                    }
                }
            }
        """.trimIndent()
        val configuration = ServerConfiguration(
            jenkinsConfig = JenkinsConfiguration(filePatterns = listOf("pipelines/*.groovy")),
        )

        val matched = analyze(code, URI.create("file:///workspace/pipelines/release.groovy"), configuration)
        val excluded = analyze(code, jenkinsfile, configuration)

        assertEquals(1, matched.size, matched.messages().toString())
        assertEquals(emptyList(), excluded.messages())
    }

    @Test
    fun `non-Jenkins files are not analyzed`() {
        val code = """
            pipeline {
                stages {
                    stage('Build') {
                    }
                }
            }
        """.trimIndent()

        assertEquals(emptyList(), analyze(code, URI.create("file:///workspace/src/Build.groovy")).messages())
    }

    @Test
    fun `braces inside strings do not end the stage early`() {
        val code = """
            pipeline {
                agent any
                stages {
                    stage('Build') {
                        when { expression { return env.MARKER == '}' } }
                        steps {
                            echo '}'
                        }
                    }
                }
            }
        """.trimIndent()

        assertEquals(emptyList(), analyze(code).messages())
    }

    @Test
    fun `steps inside a block comment do not satisfy the stage`() {
        val code = """
            pipeline {
                agent any
                stages {
                    stage('Build') {
                        /* steps { sh 'make' } */
                    }
                }
            }
        """.trimIndent()

        assertEquals(1, analyze(code).size)
    }

    @Test
    fun `steps of a later stage do not satisfy an earlier stage`() {
        val code = """
            pipeline {
                stages {
                    stage('Build') {
                        // Missing steps
                    }
                    stage('Test') {
                        steps {
                            echo 'ok'
                        }
                    }
                }
            }
        """.trimIndent()

        val diagnostics = analyze(code)

        assertEquals(1, diagnostics.size, diagnostics.messages().toString())
        assertTrue(diagnostics.single().message.contains("'Build'"))
    }

    @Test
    fun `commented out stages are ignored`() {
        val code = """
            pipeline {
                // stage('Build') {
                // }
                stages {
                    // stage('Test') {
                    // }
                    stage('Real') {
                        steps { echo 'ok' }
                    }
                }
            }
        """.trimIndent()

        assertEquals(emptyList(), analyze(code).messages())
    }

    @Test
    fun `no AST yields no diagnostics instead of guessing`(): Unit = runBlocking {
        val context = object : RuleContext {
            override fun getAst(): Any? = null

            override fun hasErrors(): Boolean = false
        }
        val code = """
            pipeline {
                stages {
                    stage('Build') {
                    }
                }
            }
        """.trimIndent()

        assertEquals(emptyList(), JenkinsPipelineStageRule().analyze(jenkinsfile, code, context))
    }

    @Test
    fun `supplementary characters before a stage keep every finding and use UTF-16 ranges`() {
        val code = """
            pipeline {
                agent any
                stages {
                    /* ${"\uD83D\uDE00"} */ stage('A') {}
                    stage('B') {}
                }
            }
        """.trimIndent()

        val diagnostics = analyze(code)

        assertEquals(
            listOf(Range(Position(3, 17), Position(3, 27)), Range(Position(4, 8), Position(4, 18))),
            diagnostics.map { it.range },
            diagnostics.messages().toString(),
        )
        assertEquals("stage('A')", code.lines()[3].substring(17, 27))
    }

    @Test
    fun `a finding whose AST position no longer matches the text is dropped alone`(): Unit = runBlocking {
        val compiled = """
            pipeline {
                agent any
                stages {
                    stage('A') {}
                    stage('B') {}
                }
            }
        """.trimIndent()
        val current = compiled.replace("        stage('A') {}", "        sh('A')")
        val compilationService = GroovyCompilationService()
        compilationService.compile(jenkinsfile, compiled)
        val staleAst = compilationService.getAst(jenkinsfile)
        val context = object : RuleContext {
            override fun getAst(): Any? = staleAst

            override fun hasErrors(): Boolean = false
        }

        val diagnostics = JenkinsPipelineStageRule().analyze(jenkinsfile, current, context)

        assertEquals(
            listOf("Stage 'B' is missing a 'steps', 'stages', 'parallel' or 'matrix' block"),
            diagnostics.messages(),
        )
        assertEquals(Range(Position(4, 8), Position(4, 18)), diagnostics.single().range)
    }
}

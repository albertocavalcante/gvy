package com.github.albertocavalcante.groovylsp.codenarc

import com.github.albertocavalcante.groovylsp.test.MockConfigurationProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.net.URI
import kotlin.test.assertNotNull

/**
 * Integration test to verify that the JsonException fix works with real Jenkins pipeline code.
 *
 * This test validates that the fix for the groovy.json.JsonException issue
 * (caused by CodeNarc trying to parse Groovy DSL rulesets as JSON) works
 * with actual Jenkins pipeline syntax.
 */
@DisplayName("CodeNarc Jenkins Integration Tests")
class CodeNarcJenkinsIntegrationTest {

    private lateinit var service: CodeNarcService
    private val jenkinsUri = URI.create("file:///Jenkinsfile")

    @BeforeEach
    fun setUp() {
        service = CodeNarcService(MockConfigurationProvider())
    }

    @Test
    fun `should analyze Jenkins pipeline without JsonException`() = runTest {
        val jenkinsPipeline = createTypicalJenkinsPipeline()

        // The fix should prevent JsonException and allow analysis to proceed
        val violations = assertDoesNotThrow {
            service.analyzeString(jenkinsPipeline, jenkinsUri)
        }

        // Analysis should succeed without JsonException
        assertNotNull(violations, "Jenkins pipeline analysis should succeed")

        // Log results for verification
        println("Jenkins pipeline analysis completed successfully!")
        println("Found ${violations.size} violations")
        violations.forEach { violation ->
            println("  - ${violation.source}: ${violation.message}")
        }
    }

    /**
     * Creates a typical Jenkins pipeline for testing purposes.
     */
    private fun createTypicalJenkinsPipeline(): String = """
        pipeline {
            agent any

            environment {
                APP_NAME = 'my-app'
                DEPLOY_ENV = 'staging'
            }

            stages {
                stage('Build') {
                    steps {
                        sh 'echo "Building app"'
                        sh './gradlew build'
                    }
                }

                stage('Test') {
                    steps {
                        sh './gradlew test'
                        publishTestResults testResultsPattern: 'build/test-results/**/*.xml'
                    }
                }

                stage('Deploy') {
                    when {
                        branch 'main'
                    }
                    steps {
                        script {
                            def deployTarget = 'staging'
                            echo "Deploying to staging"
                            sh "kubectl apply -f k8s/"
                        }
                    }
                }
            }

            post {
                always {
                    cleanWs()
                }
                failure {
                    emailext(
                        subject: "Pipeline Failed",
                        body: "Build failed. Check console output.",
                        to: "admin@company.com"
                    )
                }
            }
        }
    """.trimIndent()

    @Test
    fun `should analyze simple Jenkins shared library code`() = runTest {
        // Simple Jenkins shared library code
        val sharedLibraryCode = """
            def call(Map config) {
                pipeline {
                    agent any

                    stages {
                        stage('Build') {
                            steps {
                                sh 'mvn clean compile'
                            }
                        }

                        stage('Test') {
                            steps {
                                sh 'mvn test'
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        // Should work without JsonException
        val violations = assertDoesNotThrow {
            service.analyzeString(sharedLibraryCode, jenkinsUri)
        }

        assertNotNull(violations, "Shared library analysis should succeed")
        println("Shared library analysis completed successfully!")
        println("Found ${violations.size} violations")
    }

    @Test
    fun `should analyze simple Jenkins vars script`() = runTest {
        // Simple Jenkins vars script
        val varsScript = """
            def call(String environment) {
                echo "Deploying to: " + environment

                if (environment == 'production') {
                    sh 'kubectl apply -f prod-config.yaml'
                } else {
                    sh 'kubectl apply -f staging-config.yaml'
                }

                return "Deployment completed"
            }
        """.trimIndent()

        // Should work without JsonException
        val violations = assertDoesNotThrow {
            service.analyzeString(varsScript, jenkinsUri)
        }

        assertNotNull(violations, "Jenkins vars script analysis should succeed")
        println("Jenkins vars script analysis completed successfully!")
        println("Found ${violations.size} violations")
    }
}

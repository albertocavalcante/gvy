package com.github.albertocavalcante.groovylsp.documentation

import com.github.albertocavalcante.groovyjenkins.JenkinsContext
import com.github.albertocavalcante.groovyjenkins.JenkinsPluginManager
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Documentation provider for Jenkins Pipeline steps.
 *
 * Provides rich documentation for Jenkins-specific constructs like:
 * - Pipeline steps (sh, withCredentials, stage, etc.)
 * - Global variables (env, params, currentBuild)
 * - Plugin-specific steps (kubernetes, docker, etc.)
 *
 * Higher priority (100) than default providers to take precedence.
 */
class JenkinsDocProvider(
    private val jenkinsPluginManager: JenkinsPluginManager = JenkinsPluginManager(),
    private val jenkinsContext: JenkinsContext? = null,
) : PluggableDocProvider {

    private val logger = LoggerFactory.getLogger(JenkinsDocProvider::class.java)

    override val name: String = "Jenkins Documentation Provider"
    override val priority: Int = 100 // High priority - check before generic Groovy

    override fun canHandle(node: ASTNode, documentUri: URI): Boolean {
        // Only handle if this looks like a Jenkinsfile
        return jenkinsContext?.isJenkinsFile(documentUri) ?: isLikelyJenkinsFile(documentUri)
    }

    private fun isLikelyJenkinsFile(uri: URI): Boolean {
        val path = uri.path.lowercase()
        return path.endsWith("jenkinsfile") ||
            path.endsWith(".jenkinsfile") ||
            path.contains("/vars/") ||
            path.endsWith(".groovy")
    }

    override fun generateDoc(node: ASTNode, model: GroovyAstModel, documentUri: URI): GroovyDocumentation? {
        // Handle method calls (potential Jenkins steps)
        if (node is MethodCallExpression) {
            return generateStepDoc(node)
        }

        return null
    }

    private fun generateStepDoc(methodCall: MethodCallExpression): GroovyDocumentation? {
        val stepName = methodCall.methodAsString ?: return null

        // Try to get metadata from plugin manager
        val metadata = runBlocking {
            jenkinsPluginManager.resolveStepMetadata(stepName)
        }

        return if (metadata != null) {
            val content = buildString {
                appendLine("### `$stepName`")
                appendLine()

                metadata.documentation?.let {
                    appendLine(it)
                    appendLine()
                }

                appendLine("**Plugin:** ${metadata.plugin}")
                appendLine()

                if (metadata.parameters.isNotEmpty()) {
                    appendLine("**Parameters:**")
                    metadata.parameters.forEach { (paramName, param) ->
                        appendLine("- `$paramName`: ${param.type}${if (param.required) " *(required)*" else ""}")
                    }
                }
            }

            GroovyDocumentation.markdown(
                content = content,
                source = "Jenkins Plugin: ${metadata.plugin}",
            )
        } else {
            // Fallback: provide basic step info
            generateFallbackDoc(stepName)
        }
    }

    private fun generateFallbackDoc(stepName: String): GroovyDocumentation? {
        // Check if it's a known bundled step
        val knownSteps = setOf(
            "sh", "bat", "powershell", "echo", "error", "input",
            "node", "stage", "parallel", "steps", "script",
            "withEnv", "withCredentials", "timeout", "retry", "sleep",
            "dir", "deleteDir", "fileExists", "readFile", "writeFile",
            "checkout", "git", "stash", "unstash",
            "archiveArtifacts", "junit", "publishHTML",
        )

        return if (stepName in knownSteps) {
            GroovyDocumentation.markdown(
                content = """
                    ### `$stepName`
                    
                    Jenkins Pipeline step.
                    
                    [View documentation](https://www.jenkins.io/doc/pipeline/steps/)
                """.trimIndent(),
                source = "Jenkins Pipeline",
            )
        } else {
            null
        }
    }

    override fun getQuickNavigateInfo(node: ASTNode): String? {
        if (node is MethodCallExpression) {
            val stepName = node.methodAsString ?: return null
            return "Jenkins step: $stepName"
        }
        return null
    }
}

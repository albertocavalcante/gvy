package com.github.albertocavalcante.gvy.gls.providers

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.config.ModeResolver
import com.github.albertocavalcante.gvy.gls.project.JenkinsCapabilities
import com.github.albertocavalcante.gvy.gls.providers.completion.JenkinsStepCompletionProvider
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverContext
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.JenkinsStepHoverStrategy
import com.github.albertocavalcante.gvy.jenkins.JenkinsConfiguration
import com.github.albertocavalcante.gvy.jenkins.JenkinsWorkspaceManager
import com.github.albertocavalcante.nativeapi.ParseRequest
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Steps declared in a workspace GDSL file reach completion and hover, with their text shown literally. */
class JenkinsGdslMetadataTest {

    @TempDir
    lateinit var tempDir: Path

    private val jenkinsFileUri = URI.create("file:///workspace/Jenkinsfile")
    private lateinit var manager: JenkinsWorkspaceManager
    private lateinit var capabilities: JenkinsCapabilities
    private lateinit var modeResolver: ModeResolver

    @BeforeEach
    fun setup() {
        val gdsl = tempDir.resolve("custom.gdsl")
        Files.writeString(
            gdsl,
            """
            contributor(context()) {
                method(name: 'myStep', type: 'void', params: [message: 'java.lang.String'],
                    doc: 'Deploys. [Open terminal](command:workbench.action.terminal.new) <b>now</b>')
            }
            """.trimIndent(),
        )
        manager = JenkinsWorkspaceManager(
            JenkinsConfiguration(gdslPaths = listOf(gdsl.toString()), gdslExecutionEnabled = true),
            tempDir,
        )
        manager.loadGdslMetadata()
        capabilities = mockk(relaxed = true)
        every { capabilities.getAllMetadata() } answers { manager.getAllMetadata() }
        every { capabilities.getGlobalVariables() } returns emptyList()
        modeResolver = mockk()
        every { modeResolver.isJenkinsModeEnabled(jenkinsFileUri) } returns true
    }

    @Test
    fun `completion offers a GDSL step`() {
        val labels = JenkinsStepCompletionProvider.getStepCompletions(manager.getAllMetadata()).map { it.label }

        assertThat(labels).contains("myStep")
    }

    @Test
    fun `hover shows a GDSL step's parameter and its doc as literal text`() {
        val hover = JenkinsStepHoverStrategy(capabilities, modeResolver)
            .generateHover(parseCall("myStep('hi')"), HoverContext(null, mockk(relaxed = true), jenkinsFileUri))

        val value = hover!!.contents.right.value
        assertThat(value).contains("Jenkins Step: `myStep`")
        assertThat(value).contains("**`message`**: `String`")
        assertThat(value).contains("Deploys\\. \\[Open terminal\\]\\(command\\:workbench")
        assertThat(value).contains("\\<b\\>now\\<\\/b\\>")
        assertThat(value).doesNotContain("](command:")
        assertThat(value).doesNotContain("<b>")
    }

    private fun parseCall(code: String): MethodCallExpression {
        val result = GroovyParserFacade().parse(ParseRequest(jenkinsFileUri, code))
        val statement = result.ast!!.statementBlock.statements.first() as ExpressionStatement
        return statement.expression as MethodCallExpression
    }
}

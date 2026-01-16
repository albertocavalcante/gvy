package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovyjenkins.GlobalVariable
import com.github.albertocavalcante.groovyjenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.MergedParameter
import com.github.albertocavalcante.groovyjenkins.metadata.MergedStepMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.StepCategory
import com.github.albertocavalcante.groovyjenkins.metadata.extracted.StepScope
import com.github.albertocavalcante.groovylsp.config.ModeResolver
import com.github.albertocavalcante.groovylsp.project.JenkinsCapabilities
import com.github.albertocavalcante.groovylsp.providers.hover.HoverContext
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.MarkupKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path

/**
 * Unit tests for JenkinsStepHoverStrategy.
 *
 * Tests the hover functionality for Jenkins Pipeline steps and vars/ global variables.
 */
class JenkinsStepHoverStrategyTest {

    private lateinit var jenkinsCapabilities: JenkinsCapabilities
    private lateinit var modeResolver: ModeResolver
    private lateinit var strategy: JenkinsStepHoverStrategy
    private lateinit var context: HoverContext

    private val jenkinsFileUri = URI.create("file:///workspace/Jenkinsfile")

    @BeforeEach
    fun setup() {
        jenkinsCapabilities = mockk(relaxed = true)
        modeResolver = mockk()
        strategy = JenkinsStepHoverStrategy(jenkinsCapabilities, modeResolver)

        context = HoverContext(
            moduleNode = null,
            contentGenerator = mockk(relaxed = true),
            documentUri = jenkinsFileUri,
        )
    }

    @Test
    fun `canHandle returns true for MethodCallExpression with jenkinsCapabilities`() {
        val node = mockk<MethodCallExpression>()

        assertThat(strategy.canHandle(node)).isTrue()
    }

    @Test
    fun `canHandle returns false for non-MethodCallExpression`() {
        val node = mockk<VariableExpression>()

        assertThat(strategy.canHandle(node)).isFalse()
    }

    @Test
    fun `canHandle returns false when jenkinsCapabilities is null`() {
        val strategyWithoutCapabilities = JenkinsStepHoverStrategy(null, modeResolver)
        val node = mockk<MethodCallExpression>()

        assertThat(strategyWithoutCapabilities.canHandle(node)).isFalse()
    }

    @Test
    fun `generateHover returns null when Jenkins mode not enabled`() {
        val node = createMethodCallExpression("echo")
        every { modeResolver.isJenkinsModeEnabled(jenkinsFileUri) } returns false

        val result = strategy.generateHover(node, context)

        assertThat(result).isNull()
    }

    @Test
    fun `generateHover returns null for non-MethodCallExpression`() {
        val node = mockk<VariableExpression>()
        every { modeResolver.isJenkinsModeEnabled(jenkinsFileUri) } returns true

        val result = strategy.generateHover(node, context)

        assertThat(result).isNull()
    }

    @Test
    fun `generateHover returns null when method name cannot be determined`() {
        val node = mockk<MethodCallExpression>()
        every { node.methodAsString } returns null
        every { modeResolver.isJenkinsModeEnabled(jenkinsFileUri) } returns true

        val result = strategy.generateHover(node, context)

        assertThat(result).isNull()
    }

    @Test
    fun `generateHover creates hover for vars global variable with documentation`() {
        val node = createMethodCallExpression("myCustomStep")
        val globalVar = GlobalVariable(
            name = "myCustomStep",
            path = Path.of("/workspace/vars/myCustomStep.groovy"),
            documentation = "This is a custom step from shared library.\n\nIt does something useful.",
            callLineNumber = 5,
        )

        every { modeResolver.isJenkinsModeEnabled(jenkinsFileUri) } returns true
        every { jenkinsCapabilities.getGlobalVariables() } returns listOf(globalVar)

        val result = strategy.generateHover(node, context)

        assertThat(result).isNotNull()
        val markupContent = result!!.contents.right
        assertThat(markupContent.kind).isEqualTo(MarkupKind.MARKDOWN)
        assertThat(markupContent.value).contains("Jenkins Shared Library: `myCustomStep`")
        assertThat(markupContent.value).contains("This is a custom step from shared library")
        assertThat(markupContent.value).contains("**Source:** `myCustomStep.groovy`")

        // Check hover range
        assertThat(result.range).isNotNull()
        assertThat(result.range.start.line).isEqualTo(0)
        assertThat(result.range.start.character).isEqualTo(0)
    }

    @Test
    fun `generateHover creates hover for vars global variable without documentation`() {
        val node = createMethodCallExpression("undocumentedStep")
        val globalVar = GlobalVariable(
            name = "undocumentedStep",
            path = Path.of("/workspace/vars/undocumentedStep.groovy"),
            documentation = "",
            callLineNumber = 1,
        )

        every { modeResolver.isJenkinsModeEnabled(jenkinsFileUri) } returns true
        every { jenkinsCapabilities.getGlobalVariables() } returns listOf(globalVar)

        val result = strategy.generateHover(node, context)

        assertThat(result).isNotNull()
        val markupContent = result!!.contents.right
        assertThat(markupContent.kind).isEqualTo(MarkupKind.MARKDOWN)
        assertThat(markupContent.value).contains("Jenkins Shared Library: `undocumentedStep`")
        assertThat(markupContent.value).contains("No documentation available")
        assertThat(markupContent.value).contains("Add a `vars/undocumentedStep.txt` file")
    }

    @Test
    fun `generateHover creates hover for Jenkins step with metadata`() {
        val node = createMethodCallExpression("echo")
        val metadata = createMockMetadata(
            stepName = "echo",
            stepDoc = "Prints a message to the console.",
            plugin = "workflow-basic-steps",
            params = mapOf(
                "message" to MergedParameter(
                    name = "message",
                    type = "String",
                    defaultValue = null,
                    description = "The message to print",
                    required = true,
                    validValues = null,
                    examples = emptyList(),
                ),
            ),
        )

        every { modeResolver.isJenkinsModeEnabled(jenkinsFileUri) } returns true
        every { jenkinsCapabilities.getGlobalVariables() } returns emptyList()
        every { jenkinsCapabilities.getAllMetadata() } returns metadata

        val result = strategy.generateHover(node, context)

        assertThat(result).isNotNull()
        val markupContent = result!!.contents.right
        assertThat(markupContent.kind).isEqualTo(MarkupKind.MARKDOWN)
        assertThat(markupContent.value).contains("Jenkins Step: `echo`")
        assertThat(markupContent.value).contains("Prints a message to the console")
        assertThat(markupContent.value).contains("**Plugin:** workflow-basic-steps")
        assertThat(markupContent.value).contains("### Parameters")
        assertThat(markupContent.value).contains("**`message`**: `String` *(required)*")
        assertThat(markupContent.value).contains("The message to print")
    }

    @Test
    fun `generateHover creates hover for Jenkins step with optional parameters`() {
        val node = createMethodCallExpression("readFile")
        val metadata = createMockMetadata(
            stepName = "readFile",
            stepDoc = "Read file from workspace.",
            plugin = "workflow-basic-steps",
            params = mapOf(
                "file" to MergedParameter(
                    name = "file",
                    type = "String",
                    defaultValue = null,
                    description = "Path to the file",
                    required = true,
                    validValues = null,
                    examples = emptyList(),
                ),
                "encoding" to MergedParameter(
                    name = "encoding",
                    type = "String",
                    defaultValue = "UTF-8",
                    description = "File encoding",
                    required = false,
                    validValues = null,
                    examples = emptyList(),
                ),
            ),
        )

        every { modeResolver.isJenkinsModeEnabled(jenkinsFileUri) } returns true
        every { jenkinsCapabilities.getGlobalVariables() } returns emptyList()
        every { jenkinsCapabilities.getAllMetadata() } returns metadata

        val result = strategy.generateHover(node, context)

        assertThat(result).isNotNull()
        val markupContent = result!!.contents.right
        assertThat(markupContent.value).contains("Jenkins Step: `readFile`")
        assertThat(markupContent.value).contains("**`file`**: `String` *(required)*")
        assertThat(markupContent.value).contains("**`encoding`**: `String` (default: `UTF-8`)")
        assertThat(markupContent.value).doesNotContain("*(required)*\n  - **`encoding`")
    }

    @Test
    fun `generateHover returns null when step not found in metadata`() {
        val node = createMethodCallExpression("unknownStep")
        val metadata = createMockMetadata(
            stepName = "echo",
            stepDoc = "Echo step",
            plugin = null,
            params = emptyMap(),
        )

        every { modeResolver.isJenkinsModeEnabled(jenkinsFileUri) } returns true
        every { jenkinsCapabilities.getGlobalVariables() } returns emptyList()
        every { jenkinsCapabilities.getAllMetadata() } returns metadata

        val result = strategy.generateHover(node, context)

        assertThat(result).isNull()
    }

    @Test
    fun `generateHover returns null when getAllMetadata returns null`() {
        val node = createMethodCallExpression("echo")

        every { modeResolver.isJenkinsModeEnabled(jenkinsFileUri) } returns true
        every { jenkinsCapabilities.getGlobalVariables() } returns emptyList()
        every { jenkinsCapabilities.getAllMetadata() } returns null

        val result = strategy.generateHover(node, context)

        assertThat(result).isNull()
    }

    @Test
    fun `generateHover prefers vars global variable over step metadata`() {
        val node = createMethodCallExpression("echo")
        val globalVar = GlobalVariable(
            name = "echo",
            path = Path.of("/workspace/vars/echo.groovy"),
            documentation = "Custom echo from shared library",
            callLineNumber = 1,
        )
        val metadata = createMockMetadata(
            stepName = "echo",
            stepDoc = "Built-in echo step",
            plugin = "workflow-basic-steps",
            params = emptyMap(),
        )

        every { modeResolver.isJenkinsModeEnabled(jenkinsFileUri) } returns true
        every { jenkinsCapabilities.getGlobalVariables() } returns listOf(globalVar)
        every { jenkinsCapabilities.getAllMetadata() } returns metadata

        val result = strategy.generateHover(node, context)

        assertThat(result).isNotNull()
        val markupContent = result!!.contents.right
        // Should prefer the vars/ global variable
        assertThat(markupContent.value).contains("Jenkins Shared Library: `echo`")
        assertThat(markupContent.value).contains("Custom echo from shared library")
        assertThat(markupContent.value).doesNotContain("Built-in echo step")
    }

    @Test
    fun `generateHover creates hover for step without plugin info`() {
        val node = createMethodCallExpression("sh")
        val metadata = createMockMetadata(
            stepName = "sh",
            stepDoc = "Execute shell script",
            plugin = null,
            params = mapOf(
                "script" to MergedParameter(
                    name = "script",
                    type = "String",
                    defaultValue = null,
                    description = "Shell script to execute",
                    required = true,
                    validValues = null,
                    examples = emptyList(),
                ),
            ),
        )

        every { modeResolver.isJenkinsModeEnabled(jenkinsFileUri) } returns true
        every { jenkinsCapabilities.getGlobalVariables() } returns emptyList()
        every { jenkinsCapabilities.getAllMetadata() } returns metadata

        val result = strategy.generateHover(node, context)

        assertThat(result).isNotNull()
        val markupContent = result!!.contents.right
        assertThat(markupContent.value).contains("Jenkins Step: `sh`")
        assertThat(markupContent.value).contains("Execute shell script")
        assertThat(markupContent.value).doesNotContain("**Plugin:**")
    }

    @Test
    fun `generateHover creates hover for step without parameters`() {
        val node = createMethodCallExpression("checkout")
        val metadata = createMockMetadata(
            stepName = "checkout",
            stepDoc = "Check out from version control",
            plugin = "workflow-scm-step",
            params = emptyMap(),
        )

        every { modeResolver.isJenkinsModeEnabled(jenkinsFileUri) } returns true
        every { jenkinsCapabilities.getGlobalVariables() } returns emptyList()
        every { jenkinsCapabilities.getAllMetadata() } returns metadata

        val result = strategy.generateHover(node, context)

        assertThat(result).isNotNull()
        val markupContent = result!!.contents.right
        assertThat(markupContent.value).contains("Jenkins Step: `checkout`")
        assertThat(markupContent.value).contains("Check out from version control")
        assertThat(markupContent.value).doesNotContain("### Parameters")
    }

    // Helper function to create a mock MethodCallExpression
    private fun createMethodCallExpression(methodName: String): MethodCallExpression {
        val methodCall = mockk<MethodCallExpression>(relaxed = true)
        every { methodCall.methodAsString } returns methodName
        every { methodCall.lineNumber } returns 1
        every { methodCall.columnNumber } returns 1
        every { methodCall.lastLineNumber } returns 1
        every { methodCall.lastColumnNumber } returns methodName.length + 1
        return methodCall
    }

    // Helper function to create mock MergedJenkinsMetadata
    private fun createMockMetadata(
        stepName: String,
        stepDoc: String?,
        plugin: String?,
        params: Map<String, MergedParameter>,
    ): MergedJenkinsMetadata {
        val stepMetadata = MergedStepMetadata(
            name = stepName,
            scope = StepScope.GLOBAL,
            positionalParams = emptyList(),
            namedParams = params,
            extractedDocumentation = null,
            returnType = null,
            plugin = plugin,
            enrichedDescription = stepDoc,
            documentationUrl = null,
            category = StepCategory.UTILITY,
            examples = emptyList(),
            deprecation = null,
        )

        return MergedJenkinsMetadata(
            jenkinsVersion = "2.400",
            steps = mapOf(stepName to stepMetadata),
            globalVariables = emptyMap(),
            sections = emptyMap(),
            directives = emptyMap(),
            declarativeOptions = emptyMap(),
        )
    }
}

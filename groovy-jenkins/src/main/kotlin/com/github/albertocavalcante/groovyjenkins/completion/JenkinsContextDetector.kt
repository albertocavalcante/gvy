package com.github.albertocavalcante.groovyjenkins.completion

import com.github.albertocavalcante.groovyjenkins.metadata.declarative.DeclarativePipelineSchema

/**
 * Detects the cursor context within a Jenkinsfile to provide context-aware completions.
 *
 * This detector identifies:
 * - Property access contexts (env., params., currentBuild.)
 * - Block contexts (post{}, options{}, agent{}, steps{}, etc.)
 * - Pipeline type (declarative vs scripted)
 * - Step parameter contexts
 */
object JenkinsContextDetector {

    // Patterns for property access
    private val ENV_DOT_PATTERN = Regex("""env\.(\w*)$""")
    private val PARAMS_DOT_PATTERN = Regex("""params\.(\w*)$""")
    private val CURRENT_BUILD_DOT_PATTERN = Regex("""currentBuild\.(\w*)$""")

    // Block opening patterns
    private val BLOCK_PATTERNS = mapOf(
        "pipeline" to Regex("""\bpipeline\s*\{"""),
        "agent" to Regex("""\bagent\s*\{"""),
        "stages" to Regex("""\bstages\s*\{"""),
        "stage" to Regex("""\bstage\s*\([^)]*\)\s*\{"""),
        "steps" to Regex("""\bsteps\s*\{"""),
        "post" to Regex("""\bpost\s*\{"""),
        "options" to Regex("""\boptions\s*\{"""),
        "environment" to Regex("""\benvironment\s*\{"""),
        "parameters" to Regex("""\bparameters\s*\{"""),
        "triggers" to Regex("""\btriggers\s*\{"""),
        "tools" to Regex("""\btools\s*\{"""),
        "when" to Regex("""\bwhen\s*\{"""),
        "script" to Regex("""\bscript\s*\{"""),
        "node" to Regex("""\bnode\s*(?:\([^)]*\))?\s*\{"""),
    )

    // Post conditions
    private val POST_CONDITIONS = setOf(
        "always", "success", "failure", "unstable",
        "changed", "fixed", "regression", "aborted", "notBuilt", "unsuccessful", "cleanup",
    )

    // Pre-compiled pattern for post conditions (avoids per-iteration regex compilation)
    private val POST_CONDITION_PATTERN = Regex("""^(${POST_CONDITIONS.joinToString("|")})\s*\{.*""")

    // Step name pattern - matches step name followed by space or end of string
    private val STEP_PATTERN = Regex("""^\s*(\w+)(?:\s|$)""")

    /**
     * Detect context from a single line at the given cursor position.
     *
     * Used for quick inline context detection (e.g., env., params.).
     *
     * @param line The current line text
     * @param position Cursor position within the line (0-indexed)
     * @return Detected context
     */
    fun detectFromLine(line: String, position: Int): JenkinsCompletionContext {
        val textBeforeCursor = if (position <= line.length) {
            line.substring(0, position)
        } else {
            line
        }

        // Try to match property access contexts first
        val propertyContext = matchPropertyContext(textBeforeCursor)
        if (propertyContext != null) return propertyContext

        // Check for step parameter context
        val stepMatch = STEP_PATTERN.find(textBeforeCursor.trim())
        if (stepMatch != null) {
            return JenkinsCompletionContext(
                isStepParameterContext = true,
                currentStepName = stepMatch.groupValues[1],
            )
        }

        return JenkinsCompletionContext()
    }

    private fun matchPropertyContext(text: String): JenkinsCompletionContext? {
        val envMatch = ENV_DOT_PATTERN.find(text)
        if (envMatch != null) {
            return JenkinsCompletionContext(
                isEnvContext = true,
                partialText = envMatch.groupValues[1],
            )
        }

        val paramsMatch = PARAMS_DOT_PATTERN.find(text)
        if (paramsMatch != null) {
            return JenkinsCompletionContext(
                isParamsContext = true,
                partialText = paramsMatch.groupValues[1],
            )
        }

        val buildMatch = CURRENT_BUILD_DOT_PATTERN.find(text)
        if (buildMatch != null) {
            return JenkinsCompletionContext(
                isCurrentBuildContext = true,
                partialText = buildMatch.groupValues[1],
            )
        }
        return null
    }

    /**
     * Detect context from a document by analyzing enclosing blocks.
     *
     * @param lines All lines in the document
     * @param lineNumber Current line number (0-indexed)
     * @param column Current column (0-indexed)
     * @return Detected context with block information
     */
    fun detectFromDocument(lines: List<String>, lineNumber: Int, column: Int): JenkinsCompletionContext {
        if (lines.isEmpty()) {
            return JenkinsCompletionContext(isTopLevel = true)
        }

        val blockStack = analyzeBlockStructure(lines, lineNumber)
        val lineContext = detectCurrentLineContext(lines, lineNumber, column)

        return buildContext(blockStack, lineContext)
    }

    /**
     * Analyze block structure up to the given line.
     */
    private fun analyzeBlockStructure(lines: List<String>, lineNumber: Int): List<String> {
        val blockStack = mutableListOf<String>()
        var braceDepth = 0

        for (i in 0 until minOf(lineNumber + 1, lines.size)) {
            val line = lines[i]
            val cleanLine = stripComments(line)

            addBlockOpenings(cleanLine, blockStack)
            addPostConditionBlocks(cleanLine, blockStack)

            braceDepth += cleanLine.count { it == '{' } - cleanLine.count { it == '}' }
            trimBlockStackToDepth(blockStack, braceDepth)
        }

        return blockStack.toList()
    }

    private fun stripComments(line: String): String {
        val commentIndex = line.indexOf("//")
        return if (commentIndex != -1) line.substring(0, commentIndex) else line
    }

    /**
     * Add any block openings found in the line to the stack, in the order they appear.
     */
    private fun addBlockOpenings(line: String, blockStack: MutableList<String>) {
        val matches = mutableListOf<Pair<Int, String>>()
        for ((blockName, pattern) in BLOCK_PATTERNS) {
            pattern.findAll(line).forEach { match ->
                matches.add(match.range.first to blockName)
            }
        }
        // Sort by position and add to stack
        matches.sortBy { it.first }
        matches.forEach { blockStack.add(it.second) }
    }

    /**
     * Add any post condition blocks found in the line to the stack.
     */
    private fun addPostConditionBlocks(line: String, blockStack: MutableList<String>) {
        val trimmedLine = line.trim()
        POST_CONDITION_PATTERN.find(trimmedLine)?.groups?.get(1)?.value?.let { condition ->
            blockStack.add(condition)
        }
    }

    /**
     * Trim block stack when braces close.
     */
    private fun trimBlockStackToDepth(blockStack: MutableList<String>, braceDepth: Int) {
        while (braceDepth < blockStack.size && blockStack.isNotEmpty()) {
            blockStack.removeAt(blockStack.size - 1)
        }
    }

    /**
     * Detect context from the current line at cursor position.
     */
    private fun detectCurrentLineContext(lines: List<String>, lineNumber: Int, column: Int): JenkinsCompletionContext {
        val currentLine = lines.getOrElse(lineNumber) { "" }
        return detectFromLine(currentLine, column)
    }

    /**
     * Build final context from block stack and line context.
     */
    private fun buildContext(
        blockStack: List<String>,
        lineContext: JenkinsCompletionContext,
    ): JenkinsCompletionContext {
        val isDeclarative = "pipeline" in blockStack
        val isScripted = !isDeclarative && "node" in blockStack
        val postCondition = blockStack.lastOrNull { it in POST_CONDITIONS }
        val currentBlock = determineCurrentBlock(blockStack)

        return JenkinsCompletionContext(
            // From line context
            isEnvContext = lineContext.isEnvContext,
            isParamsContext = lineContext.isParamsContext,
            isCurrentBuildContext = lineContext.isCurrentBuildContext,
            partialText = lineContext.partialText,

            // From block analysis
            isPostContext = "post" in blockStack || postCondition != null,
            isOptionsContext = "options" in blockStack,
            isAgentContext = "agent" in blockStack,
            isStageContext = "stage" in blockStack,
            isStepsContext = "steps" in blockStack,
            isWhenContext = "when" in blockStack,
            isEnvironmentContext = "environment" in blockStack,
            isParametersContext = "parameters" in blockStack,
            isTriggersContext = "triggers" in blockStack,
            isToolsContext = "tools" in blockStack,
            isScriptContext = "script" in blockStack,

            // Pipeline type
            isDeclarativePipeline = isDeclarative,
            isScriptedPipeline = isScripted,
            isInNode = "node" in blockStack,
            isTopLevel = blockStack.isEmpty(),

            // Additional info
            postCondition = postCondition,
            enclosingBlocks = blockStack,
            currentBlock = currentBlock,

            // Step context
            isStepParameterContext = lineContext.isStepParameterContext,
            currentStepName = lineContext.currentStepName,
        )
    }

    private fun determineCurrentBlock(blockStack: List<String>): String? = blockStack.asReversed().firstOrNull {
        DeclarativePipelineSchema.containsBlock(it)
    }
}

/**
 * Represents the detected context at the cursor position in a Jenkinsfile.
 *
 * This information is used to filter and customize completions.
 */
data class JenkinsCompletionContext(
    // Property access contexts
    val isEnvContext: Boolean = false,
    val isParamsContext: Boolean = false,
    val isCurrentBuildContext: Boolean = false,

    // Block contexts
    val isPostContext: Boolean = false,
    val isOptionsContext: Boolean = false,
    val isAgentContext: Boolean = false,
    val isStageContext: Boolean = false,
    val isStepsContext: Boolean = false,
    val isWhenContext: Boolean = false,
    val isEnvironmentContext: Boolean = false,
    val isParametersContext: Boolean = false,
    val isTriggersContext: Boolean = false,
    val isToolsContext: Boolean = false,
    val isScriptContext: Boolean = false,

    // Pipeline type
    val isDeclarativePipeline: Boolean = false,
    val isScriptedPipeline: Boolean = false,
    val isInNode: Boolean = false,
    val isTopLevel: Boolean = false,

    // Step parameter context
    val isStepParameterContext: Boolean = false,
    val currentStepName: String? = null,

    // Additional info
    val partialText: String = "",
    val postCondition: String? = null,
    val enclosingBlocks: List<String> = emptyList(),
    val currentBlock: String? = null,
)

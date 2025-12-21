package com.github.albertocavalcante.groovyjenkins.completion

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
        "pipeline" to Regex("""^\s*pipeline\s*\{"""),
        "agent" to Regex("""^\s*agent\s*\{"""),
        "stages" to Regex("""^\s*stages\s*\{"""),
        "stage" to Regex("""^\s*stage\s*\([^)]*\)\s*\{"""),
        "steps" to Regex("""^\s*steps\s*\{"""),
        "post" to Regex("""^\s*post\s*\{"""),
        "options" to Regex("""^\s*options\s*\{"""),
        "environment" to Regex("""^\s*environment\s*\{"""),
        "parameters" to Regex("""^\s*parameters\s*\{"""),
        "triggers" to Regex("""^\s*triggers\s*\{"""),
        "tools" to Regex("""^\s*tools\s*\{"""),
        "when" to Regex("""^\s*when\s*\{"""),
        "script" to Regex("""^\s*script\s*\{"""),
        "node" to Regex("""^\s*node\s*(?:\([^)]*\))?\s*\{"""),
    )

    // Post conditions
    private val POST_CONDITIONS = setOf(
        "always", "success", "failure", "unstable",
        "changed", "fixed", "regression", "aborted", "cleanup",
    )

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

        // Check for env. context
        ENV_DOT_PATTERN.find(textBeforeCursor)?.let { match ->
            return JenkinsCompletionContext(
                isEnvContext = true,
                partialText = match.groupValues[1],
            )
        }

        // Check for params. context
        PARAMS_DOT_PATTERN.find(textBeforeCursor)?.let { match ->
            return JenkinsCompletionContext(
                isParamsContext = true,
                partialText = match.groupValues[1],
            )
        }

        // Check for currentBuild. context
        CURRENT_BUILD_DOT_PATTERN.find(textBeforeCursor)?.let { match ->
            return JenkinsCompletionContext(
                isCurrentBuildContext = true,
                partialText = match.groupValues[1],
            )
        }

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

        // Track open blocks
        val blockStack = mutableListOf<String>()
        var braceDepth = 0

        // Analyze lines up to cursor
        for (i in 0 until minOf(lineNumber + 1, lines.size)) {
            val line = lines[i]

            // Check for block openings
            for ((blockName, pattern) in BLOCK_PATTERNS) {
                if (pattern.containsMatchIn(line)) {
                    blockStack.add(blockName)
                }
            }

            // Check for post condition blocks
            for (condition in POST_CONDITIONS) {
                if (line.trim().matches(Regex("""$condition\s*\{.*"""))) {
                    blockStack.add(condition)
                }
            }

            // Track brace depth for simple heuristic
            braceDepth += line.count { it == '{' }
            braceDepth -= line.count { it == '}' }

            // Remove blocks when we close them
            while (braceDepth < blockStack.size && blockStack.isNotEmpty()) {
                blockStack.removeAt(blockStack.size - 1)
            }
        }

        // Also check current line for inline context
        val currentLine = if (lineNumber < lines.size) lines[lineNumber] else ""
        val lineContext = detectFromLine(currentLine, column)

        // Determine context from block stack
        val isDeclarative = blockStack.contains("pipeline")
        val isScripted = !isDeclarative && blockStack.contains("node")
        val isInNode = blockStack.contains("node")

        // Check for post condition
        val postCondition = blockStack.lastOrNull { POST_CONDITIONS.contains(it) }

        return JenkinsCompletionContext(
            // From line context
            isEnvContext = lineContext.isEnvContext,
            isParamsContext = lineContext.isParamsContext,
            isCurrentBuildContext = lineContext.isCurrentBuildContext,
            partialText = lineContext.partialText,

            // From block analysis
            isPostContext = blockStack.contains("post") || postCondition != null,
            isOptionsContext = blockStack.contains("options"),
            isAgentContext = blockStack.contains("agent"),
            isStageContext = blockStack.contains("stage"),
            isStepsContext = blockStack.contains("steps"),
            isWhenContext = blockStack.contains("when"),
            isEnvironmentContext = blockStack.contains("environment"),
            isParametersContext = blockStack.contains("parameters"),
            isTriggersContext = blockStack.contains("triggers"),
            isToolsContext = blockStack.contains("tools"),
            isScriptContext = blockStack.contains("script"),

            // Pipeline type
            isDeclarativePipeline = isDeclarative,
            isScriptedPipeline = isScripted,
            isInNode = isInNode,
            isTopLevel = blockStack.isEmpty(),

            // Additional info
            postCondition = postCondition,
            enclosingBlocks = blockStack.toList(),

            // Step context (may be set from line or block)
            isStepParameterContext = lineContext.isStepParameterContext,
            currentStepName = lineContext.currentStepName,
        )
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
)

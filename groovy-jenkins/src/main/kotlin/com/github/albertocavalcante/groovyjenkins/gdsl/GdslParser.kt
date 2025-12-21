package com.github.albertocavalcante.groovyjenkins.gdsl

/**
 * Parses Jenkins GDSL output from `/pipeline-syntax/gdsl` endpoint.
 *
 * This parser extracts step and global variable metadata from the text format
 * that Jenkins generates. It's different from executing GDSL scripts - this
 * is text parsing of Jenkins' specific output format.
 *
 * NOTE: The groovy-gdsl module handles GDSL script execution. This parser
 * handles Jenkins-specific GDSL text format extraction. Future refactoring
 * may converge these as the groovy-dsl module evolves to support both
 * GDSL (IntelliJ) and DSLD (Eclipse) formats.
 *
 * @see <a href="https://github.com/groovy/groovy-eclipse/wiki/DSL-Descriptors">DSLD Reference</a>
 */
object GdslParser {

    // Regex patterns for parsing GDSL output
    private val METHOD_PATTERN = Regex(
        """method\s*\(\s*name:\s*'([^']+)'[^)]*\)""",
    )

    private val METHOD_FULL_PATTERN = Regex(
        """method\s*\(\s*name:\s*'([^']+)',\s*type:\s*'([^']+)'""" +
            """(?:,\s*params:\s*\[([^\]]*)\])?""" +
            """(?:,\s*namedParams:\s*\[([^\]]*)\])?""" +
            """(?:,\s*doc:\s*'([^']*(?:''[^']*)*)')?""" +
            """\s*\)""",
    )

    private val PROPERTY_PATTERN = Regex(
        """property\s*\(\s*name:\s*'([^']+)',\s*type:\s*'([^']+)'\s*\)""",
    )

    private val PARAM_PATTERN = Regex(
        """(\w+)\s*:\s*'?([^',\]]+)'?""",
    )

    private val NAMED_PARAM_PATTERN = Regex(
        """parameter\s*\(\s*name:\s*'([^']+)',\s*type:\s*'([^']+)'\s*\)""",
    )

    private val NODE_CONTEXT_START = Regex(
        """def\s+\w+\s*=\s*context\s*\(\s*scope:\s*closureScope\(\)\s*\)""",
    )

    private val SCRIPT_CONTEXT_START = Regex(
        """def\s+\w+\s*=\s*context\s*\(\s*scope:\s*scriptScope\(\)\s*\)""",
    )

    private val ENCLOSING_NODE_CHECK = Regex(
        """enclosingCall\s*\(\s*['"]node['"]\s*\)""",
    )

    /**
     * Parse GDSL content into a list of steps and global variables.
     *
     * This returns raw parsed data with potential duplicate steps (different overloads).
     *
     * @param gdsl The GDSL text content from Jenkins
     * @return Parsed result with steps and global variables
     */
    fun parse(gdsl: String): GdslParseResult {
        val steps = mutableListOf<ParsedStep>()
        val globalVariables = mutableListOf<ParsedGlobalVariable>()

        // Track context state
        var inNodeContext = false
        var nodeContextDepth = 0

        // Process line by line to track context
        val lines = gdsl.lines()
        for ((index, line) in lines.withIndex()) {
            // Check for context switches
            if (NODE_CONTEXT_START.containsMatchIn(line)) {
                inNodeContext = true
                nodeContextDepth = 0
            } else if (SCRIPT_CONTEXT_START.containsMatchIn(line)) {
                inNodeContext = false
            }

            // Track enclosing node check (indicates node-requiring steps follow)
            if (ENCLOSING_NODE_CHECK.containsMatchIn(line)) {
                inNodeContext = true
            }

            // Track brace depth for context blocks
            nodeContextDepth += line.count { it == '{' }
            nodeContextDepth -= line.count { it == '}' }

            if (nodeContextDepth < 0) {
                inNodeContext = false
                nodeContextDepth = 0
            }

            // Parse method declarations
            parseMethodLine(line)?.let { step ->
                steps.add(step.copy(requiresNode = inNodeContext))
            }

            // Parse property declarations (global variables)
            PROPERTY_PATTERN.find(line)?.let { match ->
                val (name, type) = match.destructured
                globalVariables.add(ParsedGlobalVariable(name = name, type = type))
            }
        }

        return GdslParseResult(steps = steps, globalVariables = globalVariables)
    }

    /**
     * Parse GDSL and merge overloads into single step entries.
     *
     * This is useful when you want a single definition per step name
     * with all parameters from all overloads combined.
     *
     * @param gdsl The GDSL text content
     * @return Merged result with unique step names
     */
    fun parseMerged(gdsl: String): MergedGdslParseResult {
        val raw = parse(gdsl)

        // Group by step name and merge
        val mergedSteps = raw.steps
            .groupBy { it.name }
            .mapValues { (_, overloads) ->
                val merged = overloads.first().copy(
                    parameters = overloads.flatMap { it.parameters.entries }
                        .associate { it.key to it.value },
                    namedParameters = overloads.flatMap { it.namedParameters.entries }
                        .associate { it.key to it.value },
                    takesBlock = overloads.any { it.takesBlock },
                    requiresNode = overloads.any { it.requiresNode },
                )
                merged
            }

        // Deduplicate global variables
        val uniqueGlobals = raw.globalVariables.distinctBy { it.name }

        return MergedGdslParseResult(
            steps = mergedSteps,
            globalVariables = uniqueGlobals,
        )
    }

    /**
     * Parse a single line for method declarations.
     */
    private fun parseMethodLine(line: String): ParsedStep? {
        // Skip comments and empty lines
        if (line.trim().startsWith("//") || line.isBlank()) {
            return null
        }

        // Try to find method declaration
        if (!line.contains("method(")) {
            return null
        }

        // Extract method name first
        val nameMatch = METHOD_PATTERN.find(line) ?: return null
        val name = nameMatch.groupValues[1]

        // Parse params
        val params = mutableMapOf<String, String>()
        val paramsMatch = Regex("""params:\s*\[([^\]]*)\]""").find(line)
        if (paramsMatch != null) {
            val paramsContent = paramsMatch.groupValues[1]
            PARAM_PATTERN.findAll(paramsContent).forEach { match ->
                val (paramName, paramType) = match.destructured
                params[paramName] = paramType.trim()
            }
        }

        // Parse namedParams
        val namedParams = mutableMapOf<String, String>()
        val namedParamsMatch = Regex("""namedParams:\s*\[([^\]]*)\]""").find(line)
        if (namedParamsMatch != null) {
            val namedContent = namedParamsMatch.groupValues[1]
            NAMED_PARAM_PATTERN.findAll(namedContent).forEach { match ->
                val (paramName, paramType) = match.destructured
                namedParams[paramName] = paramType.trim()
            }
        }

        // Parse documentation - handle escaped quotes
        val docMatch = Regex("""doc:\s*'((?:[^'\\]|\\'|'')*)'""").find(line)
        val documentation = docMatch?.groupValues?.get(1)
            ?.replace("\\'", "'") // Unescape backslash-escaped quotes
            ?.replace("''", "'") // Unescape double-single quotes
            ?: ""

        // Check if takes closure/block
        val takesBlock = params.containsKey("body") ||
            params.values.any { it.contains("Closure", ignoreCase = true) }

        return ParsedStep(
            name = name,
            documentation = documentation,
            parameters = params,
            namedParameters = namedParams,
            takesBlock = takesBlock,
            requiresNode = false, // Will be set by caller based on context
        )
    }
}

/**
 * Result of parsing GDSL content.
 *
 * Contains all parsed steps (including overloads) and global variables.
 */
data class GdslParseResult(val steps: List<ParsedStep>, val globalVariables: List<ParsedGlobalVariable>)

/**
 * Result of parsing and merging GDSL content.
 *
 * Steps are deduplicated by name with parameters merged from all overloads.
 */
data class MergedGdslParseResult(val steps: Map<String, ParsedStep>, val globalVariables: List<ParsedGlobalVariable>)

/**
 * A parsed step from GDSL.
 */
data class ParsedStep(
    /**
     * Step function name (e.g., "sh", "echo")
     */
    val name: String,

    /**
     * Documentation string from GDSL
     */
    val documentation: String,

    /**
     * Positional parameters (from params: [...])
     */
    val parameters: Map<String, String>,

    /**
     * Named parameters (from namedParams: [...])
     */
    val namedParameters: Map<String, String>,

    /**
     * Whether this step accepts a closure body
     */
    val takesBlock: Boolean,

    /**
     * Whether this step requires a node context
     */
    val requiresNode: Boolean,
)

/**
 * A parsed global variable from GDSL.
 */
data class ParsedGlobalVariable(
    /**
     * Variable name (e.g., "env", "params")
     */
    val name: String,

    /**
     * Type class name
     */
    val type: String,
)

package com.github.albertocavalcante.diagnostics.sarif

/**
 * Registry for SARIF rule metadata.
 *
 * Provides rule descriptions, help URIs, and other metadata for SARIF output.
 * CodeNarc rules are mapped to their documentation on codenarc.org.
 */
object SarifRuleRegistry {

    private val codeNarcRules: Map<String, SarifRule> by lazy {
        buildCodeNarcRules()
    }

    private val compilerRules: Map<String, SarifRule> = mapOf(
        "CompilationError" to SarifRule(
            id = "CompilationError",
            name = "Groovy Compilation Error",
            shortDescription = SarifMessage(
                text = "A Groovy compilation error occurred",
            ),
            fullDescription = SarifMessage(
                text = "The Groovy compiler encountered an error while parsing or compiling the source code. " +
                    "This typically indicates syntax errors, missing imports, or type mismatches.",
            ),
            helpUri = "https://groovy-lang.org/documentation.html",
            defaultConfiguration = SarifRuleConfiguration(level = SarifLevel.ERROR),
            properties = SarifRuleProperties(category = "compiler"),
        ),
        "CompilationWarning" to SarifRule(
            id = "CompilationWarning",
            name = "Groovy Compilation Warning",
            shortDescription = SarifMessage(
                text = "A Groovy compilation warning occurred",
            ),
            helpUri = "https://groovy-lang.org/documentation.html",
            defaultConfiguration = SarifRuleConfiguration(level = SarifLevel.WARNING),
            properties = SarifRuleProperties(category = "compiler"),
        ),
    )

    /**
     * Gets a rule by ID, looking up in CodeNarc rules first, then compiler rules.
     */
    fun getRule(ruleId: String): SarifRule? = codeNarcRules[ruleId] ?: compilerRules[ruleId]

    /**
     * Gets all known CodeNarc rules.
     */
    fun getCodeNarcRules(): Collection<SarifRule> = codeNarcRules.values

    /**
     * Gets all known compiler rules.
     */
    fun getCompilerRules(): Collection<SarifRule> = compilerRules.values

    /**
     * Gets all known rules.
     */
    fun getAllRules(): Collection<SarifRule> = codeNarcRules.values + compilerRules.values

    /**
     * Builds the CodeNarc rule category URL suffix.
     */
    private fun getCategoryUrlSuffix(category: String): String = category.lowercase().replace(" ", "-")

    /**
     * Builds CodeNarc rules with their documentation URIs.
     *
     * This is a curated list of commonly used rules. For complete coverage,
     * consider integrating with CodeNarc's RuleRegistry at runtime.
     */
    private fun buildCodeNarcRules(): Map<String, SarifRule> {
        val rules = mutableMapOf<String, SarifRule>()

        // Basic rules
        addCodeNarcRule(rules, "EmptyClass", "basic", 3, "Reports classes without methods, fields or properties")
        addCodeNarcRule(rules, "EmptyMethod", "basic", 2, "Reports empty methods")
        addCodeNarcRule(rules, "EmptyIfStatement", "basic", 2, "Reports empty if statements")
        addCodeNarcRule(rules, "EmptyElseBlock", "basic", 2, "Reports empty else blocks")
        addCodeNarcRule(rules, "EmptyWhileStatement", "basic", 2, "Reports empty while statements")
        addCodeNarcRule(rules, "EmptyTryBlock", "basic", 2, "Reports empty try blocks")
        addCodeNarcRule(rules, "EmptyCatchBlock", "basic", 2, "Reports empty catch blocks")
        addCodeNarcRule(rules, "EmptyFinallyBlock", "basic", 2, "Reports empty finally blocks")
        addCodeNarcRule(rules, "EmptySwitchStatement", "basic", 2, "Reports empty switch statements")
        addCodeNarcRule(rules, "EmptySynchronizedStatement", "basic", 2, "Reports empty synchronized statements")

        // Formatting rules
        addCodeNarcRule(rules, "SpaceAfterComma", "formatting", 3, "Checks for space after commas")
        addCodeNarcRule(rules, "SpaceAroundOperator", "formatting", 3, "Checks for space around operators")
        addCodeNarcRule(rules, "SpaceBeforeOpeningBrace", "formatting", 3, "Checks for space before opening braces")
        addCodeNarcRule(rules, "SpaceAfterOpeningBrace", "formatting", 3, "Checks for space after opening braces")
        addCodeNarcRule(rules, "SpaceBeforeClosingBrace", "formatting", 3, "Checks for space before closing braces")
        addCodeNarcRule(rules, "LineLength", "formatting", 3, "Checks line length")
        addCodeNarcRule(rules, "Indentation", "formatting", 3, "Checks indentation consistency")

        // Unused rules
        addCodeNarcRule(rules, "UnusedVariable", "unused", 2, "Reports unused variables")
        addCodeNarcRule(rules, "UnusedPrivateField", "unused", 2, "Reports unused private fields")
        addCodeNarcRule(rules, "UnusedPrivateMethod", "unused", 2, "Reports unused private methods")
        addCodeNarcRule(rules, "UnusedImport", "imports", 3, "Reports unused imports")
        addCodeNarcRule(rules, "UnusedArray", "unused", 2, "Reports unused arrays")
        addCodeNarcRule(rules, "UnusedObject", "unused", 2, "Reports unused objects")
        addCodeNarcRule(rules, "UnusedMethodParameter", "unused", 2, "Reports unused method parameters")

        // Naming rules
        addCodeNarcRule(rules, "ClassName", "naming", 2, "Checks class names follow conventions")
        addCodeNarcRule(rules, "MethodName", "naming", 2, "Checks method names follow conventions")
        addCodeNarcRule(rules, "VariableName", "naming", 2, "Checks variable names follow conventions")
        addCodeNarcRule(rules, "PackageName", "naming", 2, "Checks package names follow conventions")
        addCodeNarcRule(rules, "FieldName", "naming", 2, "Checks field names follow conventions")
        addCodeNarcRule(rules, "ParameterName", "naming", 2, "Checks parameter names follow conventions")

        // Groovyism rules
        addCodeNarcRule(rules, "ExplicitArrayListInstantiation", "groovyism", 3, "Use [] instead of new ArrayList()")
        addCodeNarcRule(rules, "ExplicitHashMapInstantiation", "groovyism", 3, "Use [:] instead of new HashMap()")
        addCodeNarcRule(
            rules,
            "ExplicitLinkedListInstantiation",
            "groovyism",
            3,
            "Use [] as LinkedList instead of new LinkedList()",
        )
        addCodeNarcRule(rules, "GStringAsMapKey", "groovyism", 2, "Warns about using GString as map key")
        addCodeNarcRule(
            rules,
            "ClosureAsLastMethodParameter",
            "groovyism",
            3,
            "Suggests using closure as last parameter",
        )

        // Size/Complexity rules
        addCodeNarcRule(rules, "MethodSize", "size", 2, "Reports methods exceeding size threshold")
        addCodeNarcRule(rules, "ClassSize", "size", 2, "Reports classes exceeding size threshold")
        addCodeNarcRule(rules, "CyclomaticComplexity", "size", 2, "Reports high cyclomatic complexity")
        addCodeNarcRule(rules, "NestedBlockDepth", "size", 2, "Reports deeply nested blocks")
        addCodeNarcRule(rules, "MethodCount", "size", 2, "Reports classes with too many methods")
        addCodeNarcRule(rules, "ParameterCount", "size", 2, "Reports methods with too many parameters")

        // Exception rules
        addCodeNarcRule(rules, "CatchException", "exceptions", 2, "Avoid catching Exception")
        addCodeNarcRule(rules, "CatchThrowable", "exceptions", 2, "Avoid catching Throwable")
        addCodeNarcRule(rules, "CatchError", "exceptions", 2, "Avoid catching Error")
        addCodeNarcRule(rules, "ThrowException", "exceptions", 2, "Avoid throwing Exception")
        addCodeNarcRule(rules, "ThrowError", "exceptions", 2, "Avoid throwing Error")
        addCodeNarcRule(rules, "ThrowThrowable", "exceptions", 2, "Avoid throwing Throwable")
        addCodeNarcRule(rules, "ReturnNullFromCatchBlock", "exceptions", 2, "Avoid returning null from catch blocks")

        // Security rules
        addCodeNarcRule(rules, "InsecureRandom", "security", 1, "java.util.Random is not cryptographically secure")
        addCodeNarcRule(rules, "SystemExit", "security", 1, "System.exit() should not be called")
        addCodeNarcRule(rules, "FileCreateTempFile", "security", 2, "Use Files.createTempFile() instead")
        addCodeNarcRule(rules, "JavaIoPackageAccess", "security", 2, "Avoid direct java.io access")

        // Unnecessary rules
        addCodeNarcRule(rules, "UnnecessaryBooleanExpression", "unnecessary", 3, "Simplify boolean expressions")
        addCodeNarcRule(rules, "UnnecessaryBooleanInstantiation", "unnecessary", 3, "Use Boolean.TRUE/FALSE")
        addCodeNarcRule(rules, "UnnecessaryCollectCall", "unnecessary", 3, "Simplify collect calls")
        addCodeNarcRule(rules, "UnnecessaryDefInFieldDeclaration", "unnecessary", 3, "Remove unnecessary def")
        addCodeNarcRule(rules, "UnnecessaryDefInMethodDeclaration", "unnecessary", 3, "Remove unnecessary def")
        addCodeNarcRule(rules, "UnnecessaryDefInVariableDeclaration", "unnecessary", 3, "Remove unnecessary def")
        addCodeNarcRule(rules, "UnnecessaryGetter", "unnecessary", 3, "Use property access instead of getter")
        addCodeNarcRule(rules, "UnnecessarySetter", "unnecessary", 3, "Use property access instead of setter")
        addCodeNarcRule(rules, "UnnecessaryReturnKeyword", "unnecessary", 3, "Remove unnecessary return keyword")

        // Braces rules
        addCodeNarcRule(rules, "IfStatementBraces", "braces", 3, "If statements should use braces")
        addCodeNarcRule(rules, "ElseBlockBraces", "braces", 3, "Else blocks should use braces")
        addCodeNarcRule(rules, "WhileStatementBraces", "braces", 3, "While statements should use braces")
        addCodeNarcRule(rules, "ForStatementBraces", "braces", 3, "For statements should use braces")

        return rules
    }

    private fun addCodeNarcRule(
        rules: MutableMap<String, SarifRule>,
        id: String,
        category: String,
        priority: Int,
        description: String,
    ) {
        val level = when (priority) {
            1 -> SarifLevel.ERROR
            2 -> SarifLevel.WARNING
            else -> SarifLevel.NOTE
        }

        rules[id] = SarifRule(
            id = id,
            name = "${id}Rule",
            shortDescription = SarifMessage(text = description),
            helpUri = "https://codenarc.org/codenarc-rules-${getCategoryUrlSuffix(category)}.html#$id",
            defaultConfiguration = SarifRuleConfiguration(level = level),
            properties = SarifRuleProperties(
                priority = priority,
                category = category,
            ),
        )
    }
}

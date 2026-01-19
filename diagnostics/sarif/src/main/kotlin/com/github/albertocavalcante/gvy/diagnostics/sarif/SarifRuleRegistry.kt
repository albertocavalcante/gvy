package com.github.albertocavalcante.gvy.diagnostics.sarif

/**
 * Registry for SARIF rule metadata.
 *
 * Provides rule descriptions, help URIs, and other metadata for SARIF output.
 * CodeNarc rules are mapped to their documentation on codenarc.org.
 */
object SarifRuleRegistry {

    /**
     * CodeNarc priority levels mapped to SARIF severity.
     * Priority 1 = highest severity (ERROR), 3 = lowest (NOTE).
     */
    private object CodeNarcPriority {
        /** High priority - maps to SARIF ERROR level */
        const val HIGH = 1

        /** Medium priority - maps to SARIF WARNING level */
        const val MEDIUM = 2

        /** Low priority - maps to SARIF NOTE level */
        const val LOW = 3
    }

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
        addBasicRules(rules)
        addFormattingRules(rules)
        addUnusedRules(rules)
        addNamingRules(rules)
        addGroovyismRules(rules)
        addSizeComplexityRules(rules)
        addExceptionRules(rules)
        addSecurityRules(rules)
        addUnnecessaryRules(rules)
        addBracesRules(rules)
        return rules
    }

    private fun addBasicRules(rules: MutableMap<String, SarifRule>) {
        addCodeNarcRule(
            rules,
            "EmptyClass",
            "basic",
            CodeNarcPriority.LOW,
            "Reports classes without methods, fields or properties",
        )
        addCodeNarcRule(rules, "EmptyMethod", "basic", CodeNarcPriority.MEDIUM, "Reports empty methods")
        addCodeNarcRule(rules, "EmptyIfStatement", "basic", CodeNarcPriority.MEDIUM, "Reports empty if statements")
        addCodeNarcRule(rules, "EmptyElseBlock", "basic", CodeNarcPriority.MEDIUM, "Reports empty else blocks")
        addCodeNarcRule(
            rules,
            "EmptyWhileStatement",
            "basic",
            CodeNarcPriority.MEDIUM,
            "Reports empty while statements",
        )
        addCodeNarcRule(rules, "EmptyTryBlock", "basic", CodeNarcPriority.MEDIUM, "Reports empty try blocks")
        addCodeNarcRule(rules, "EmptyCatchBlock", "basic", CodeNarcPriority.MEDIUM, "Reports empty catch blocks")
        addCodeNarcRule(rules, "EmptyFinallyBlock", "basic", CodeNarcPriority.MEDIUM, "Reports empty finally blocks")
        addCodeNarcRule(
            rules,
            "EmptySwitchStatement",
            "basic",
            CodeNarcPriority.MEDIUM,
            "Reports empty switch statements",
        )
        addCodeNarcRule(
            rules,
            "EmptySynchronizedStatement",
            "basic",
            CodeNarcPriority.MEDIUM,
            "Reports empty synchronized statements",
        )
    }

    private fun addFormattingRules(rules: MutableMap<String, SarifRule>) {
        addCodeNarcRule(rules, "SpaceAfterComma", "formatting", CodeNarcPriority.LOW, "Checks for space after commas")
        addCodeNarcRule(
            rules,
            "SpaceAroundOperator",
            "formatting",
            CodeNarcPriority.LOW,
            "Checks for space around operators",
        )
        addCodeNarcRule(
            rules,
            "SpaceBeforeOpeningBrace",
            "formatting",
            CodeNarcPriority.LOW,
            "Checks for space before opening braces",
        )
        addCodeNarcRule(
            rules,
            "SpaceAfterOpeningBrace",
            "formatting",
            CodeNarcPriority.LOW,
            "Checks for space after opening braces",
        )
        addCodeNarcRule(
            rules,
            "SpaceBeforeClosingBrace",
            "formatting",
            CodeNarcPriority.LOW,
            "Checks for space before closing braces",
        )
        addCodeNarcRule(rules, "LineLength", "formatting", CodeNarcPriority.LOW, "Checks line length")
        addCodeNarcRule(rules, "Indentation", "formatting", CodeNarcPriority.LOW, "Checks indentation consistency")
    }

    private fun addUnusedRules(rules: MutableMap<String, SarifRule>) {
        addCodeNarcRule(rules, "UnusedVariable", "unused", CodeNarcPriority.MEDIUM, "Reports unused variables")
        addCodeNarcRule(rules, "UnusedPrivateField", "unused", CodeNarcPriority.MEDIUM, "Reports unused private fields")
        addCodeNarcRule(
            rules,
            "UnusedPrivateMethod",
            "unused",
            CodeNarcPriority.MEDIUM,
            "Reports unused private methods",
        )
        addCodeNarcRule(rules, "UnusedImport", "imports", CodeNarcPriority.LOW, "Reports unused imports")
        addCodeNarcRule(rules, "UnusedArray", "unused", CodeNarcPriority.MEDIUM, "Reports unused arrays")
        addCodeNarcRule(rules, "UnusedObject", "unused", CodeNarcPriority.MEDIUM, "Reports unused objects")
        addCodeNarcRule(
            rules,
            "UnusedMethodParameter",
            "unused",
            CodeNarcPriority.MEDIUM,
            "Reports unused method parameters",
        )
    }

    private fun addNamingRules(rules: MutableMap<String, SarifRule>) {
        addCodeNarcRule(rules, "ClassName", "naming", CodeNarcPriority.MEDIUM, "Checks class names follow conventions")
        addCodeNarcRule(
            rules,
            "MethodName",
            "naming",
            CodeNarcPriority.MEDIUM,
            "Checks method names follow conventions",
        )
        addCodeNarcRule(
            rules,
            "VariableName",
            "naming",
            CodeNarcPriority.MEDIUM,
            "Checks variable names follow conventions",
        )
        addCodeNarcRule(
            rules,
            "PackageName",
            "naming",
            CodeNarcPriority.MEDIUM,
            "Checks package names follow conventions",
        )
        addCodeNarcRule(rules, "FieldName", "naming", CodeNarcPriority.MEDIUM, "Checks field names follow conventions")
        addCodeNarcRule(
            rules,
            "ParameterName",
            "naming",
            CodeNarcPriority.MEDIUM,
            "Checks parameter names follow conventions",
        )
    }

    private fun addGroovyismRules(rules: MutableMap<String, SarifRule>) {
        addCodeNarcRule(
            rules,
            "ExplicitArrayListInstantiation",
            "groovyism",
            CodeNarcPriority.LOW,
            "Use [] instead of new ArrayList()",
        )
        addCodeNarcRule(
            rules,
            "ExplicitHashMapInstantiation",
            "groovyism",
            CodeNarcPriority.LOW,
            "Use [:] instead of new HashMap()",
        )
        addCodeNarcRule(
            rules,
            "ExplicitLinkedListInstantiation",
            "groovyism",
            CodeNarcPriority.LOW,
            "Use [] as LinkedList instead of new LinkedList()",
        )
        addCodeNarcRule(
            rules,
            "GStringAsMapKey",
            "groovyism",
            CodeNarcPriority.MEDIUM,
            "Warns about using GString as map key",
        )
        addCodeNarcRule(
            rules,
            "ClosureAsLastMethodParameter",
            "groovyism",
            CodeNarcPriority.LOW,
            "Suggests using closure as last parameter",
        )
    }

    private fun addSizeComplexityRules(rules: MutableMap<String, SarifRule>) {
        addCodeNarcRule(
            rules,
            "MethodSize",
            "size",
            CodeNarcPriority.MEDIUM,
            "Reports methods exceeding size threshold",
        )
        addCodeNarcRule(rules, "ClassSize", "size", CodeNarcPriority.MEDIUM, "Reports classes exceeding size threshold")
        addCodeNarcRule(
            rules,
            "CyclomaticComplexity",
            "size",
            CodeNarcPriority.MEDIUM,
            "Reports high cyclomatic complexity",
        )
        addCodeNarcRule(rules, "NestedBlockDepth", "size", CodeNarcPriority.MEDIUM, "Reports deeply nested blocks")
        addCodeNarcRule(rules, "MethodCount", "size", CodeNarcPriority.MEDIUM, "Reports classes with too many methods")
        addCodeNarcRule(
            rules,
            "ParameterCount",
            "size",
            CodeNarcPriority.MEDIUM,
            "Reports methods with too many parameters",
        )
    }

    private fun addExceptionRules(rules: MutableMap<String, SarifRule>) {
        addCodeNarcRule(rules, "CatchException", "exceptions", CodeNarcPriority.MEDIUM, "Avoid catching Exception")
        addCodeNarcRule(rules, "CatchThrowable", "exceptions", CodeNarcPriority.MEDIUM, "Avoid catching Throwable")
        addCodeNarcRule(rules, "CatchError", "exceptions", CodeNarcPriority.MEDIUM, "Avoid catching Error")
        addCodeNarcRule(rules, "ThrowException", "exceptions", CodeNarcPriority.MEDIUM, "Avoid throwing Exception")
        addCodeNarcRule(rules, "ThrowError", "exceptions", CodeNarcPriority.MEDIUM, "Avoid throwing Error")
        addCodeNarcRule(rules, "ThrowThrowable", "exceptions", CodeNarcPriority.MEDIUM, "Avoid throwing Throwable")
        addCodeNarcRule(
            rules,
            "ReturnNullFromCatchBlock",
            "exceptions",
            CodeNarcPriority.MEDIUM,
            "Avoid returning null from catch blocks",
        )
    }

    private fun addSecurityRules(rules: MutableMap<String, SarifRule>) {
        addCodeNarcRule(
            rules,
            "InsecureRandom",
            "security",
            CodeNarcPriority.HIGH,
            "java.util.Random is not cryptographically secure",
        )
        addCodeNarcRule(rules, "SystemExit", "security", CodeNarcPriority.HIGH, "System.exit() should not be called")
        addCodeNarcRule(
            rules,
            "FileCreateTempFile",
            "security",
            CodeNarcPriority.MEDIUM,
            "Use Files.createTempFile() instead",
        )
        addCodeNarcRule(
            rules,
            "JavaIoPackageAccess",
            "security",
            CodeNarcPriority.MEDIUM,
            "Avoid direct java.io access",
        )
    }

    private fun addUnnecessaryRules(rules: MutableMap<String, SarifRule>) {
        addCodeNarcRule(
            rules,
            "UnnecessaryBooleanExpression",
            "unnecessary",
            CodeNarcPriority.LOW,
            "Simplify boolean expressions",
        )
        addCodeNarcRule(
            rules,
            "UnnecessaryBooleanInstantiation",
            "unnecessary",
            CodeNarcPriority.LOW,
            "Use Boolean.TRUE/FALSE",
        )
        addCodeNarcRule(rules, "UnnecessaryCollectCall", "unnecessary", CodeNarcPriority.LOW, "Simplify collect calls")
        addCodeNarcRule(
            rules,
            "UnnecessaryDefInFieldDeclaration",
            "unnecessary",
            CodeNarcPriority.LOW,
            "Remove unnecessary def",
        )
        addCodeNarcRule(
            rules,
            "UnnecessaryDefInMethodDeclaration",
            "unnecessary",
            CodeNarcPriority.LOW,
            "Remove unnecessary def",
        )
        addCodeNarcRule(
            rules,
            "UnnecessaryDefInVariableDeclaration",
            "unnecessary",
            CodeNarcPriority.LOW,
            "Remove unnecessary def",
        )
        addCodeNarcRule(
            rules,
            "UnnecessaryGetter",
            "unnecessary",
            CodeNarcPriority.LOW,
            "Use property access instead of getter",
        )
        addCodeNarcRule(
            rules,
            "UnnecessarySetter",
            "unnecessary",
            CodeNarcPriority.LOW,
            "Use property access instead of setter",
        )
        addCodeNarcRule(
            rules,
            "UnnecessaryReturnKeyword",
            "unnecessary",
            CodeNarcPriority.LOW,
            "Remove unnecessary return keyword",
        )
    }

    private fun addBracesRules(rules: MutableMap<String, SarifRule>) {
        addCodeNarcRule(rules, "IfStatementBraces", "braces", CodeNarcPriority.LOW, "If statements should use braces")
        addCodeNarcRule(rules, "ElseBlockBraces", "braces", CodeNarcPriority.LOW, "Else blocks should use braces")
        addCodeNarcRule(
            rules,
            "WhileStatementBraces",
            "braces",
            CodeNarcPriority.LOW,
            "While statements should use braces",
        )
        addCodeNarcRule(rules, "ForStatementBraces", "braces", CodeNarcPriority.LOW, "For statements should use braces")
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

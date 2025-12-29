/**
 * Jenkins pipeline ruleset - CPS safety rules ONLY.
 *
 * This ruleset is specifically designed for Jenkinsfiles and Jenkins shared libraries.
 * It includes ONLY Jenkins CPS safety rules and critical correctness rules.
 *
 * Style/formatting rules are INTENTIONALLY EXCLUDED because:
 * - Jenkins Pipeline DSL has unique formatting conventions
 * - Nested closures don't follow standard Groovy indentation
 * - Format-on-save should handle style issues instead
 *
 * Philosophy: Signal over noise. Only flag issues that can break pipelines.
 *
 * See: https://codenarc.org/codenarc-rules-jenkins.html
 */
ruleset {
    description 'Jenkins pipeline ruleset: ONLY CPS safety + critical correctness rules'

    // ===== JENKINS CPS RULES (HIGHEST PRIORITY) =====
    // These rules detect CPS transformation issues that WILL break Jenkins pipelines at runtime
    //
    // Include all bundled Jenkins CPS rules from CodeNarc:
    // - ClassNotSerializable: Classes should implement Serializable
    // - ClosureInGString: Closures in GStrings cause CPS errors
    // - CpsCallFromNonCpsMethod: CPS methods called from non-CPS methods
    // - ExpressionInCpsMethodNotSerializable: Non-serializable expressions in CPS methods
    // - ForbiddenCallInCpsMethod: Non-CPS methods called with CPS closures
    // - ObjectOverrideOnlyNonCpsMethods: Overridden Object methods must be @NonCPS
    // - ParameterOrReturnTypeNotSerializable: Parameters/returns must be Serializable
    ruleset('rulesets/jenkins.xml') {
        // Configure CpsCallFromNonCpsMethod for common Jenkins patterns
        CpsCallFromNonCpsMethod {
            cpsScriptVariableName = 'script'
            cpsPackages = []
            priority = 1  // Critical - will break pipeline
        }

        // All other Jenkins rules also priority 1 (critical)
        ClassNotSerializable { priority = 1 }
        ClosureInGString { priority = 1 }
        ExpressionInCpsMethodNotSerializable { priority = 1 }
        ForbiddenCallInCpsMethod { priority = 1 }
        ObjectOverrideOnlyNonCpsMethods { priority = 1 }
        ParameterOrReturnTypeNotSerializable { priority = 1 }
    }

    // ===== CRITICAL CORRECTNESS RULES =====
    // Only rules that catch actual bugs, not style issues
    // Priority 2 = Warning level (important but not critical)

    // Exception handling that can break pipelines
    CatchException {
        priority = 2
        doNotApplyToClassNames = '*Spec, *Test'  // Allow in tests
    }
    CatchThrowable {
        priority = 1  // Critical - catches errors too broadly
    }
    ThrowException {
        priority = 2
    }

    // Variables that are actually unused (potential bugs)
    UnusedVariable {
        priority = 2
    }

    // ===== EXPLICITLY EXCLUDED =====
    // The following rule categories are NOT included for Jenkinsfiles:
    //
    // ❌ NO rulesets/basic.xml - too many style rules
    // ❌ NO rulesets/imports.xml - import organization is not critical
    // ❌ NO rulesets/formatting.xml - style should be handled by formatter
    // ❌ NO rulesets/unnecessary.xml - code style preferences
    // ❌ NO rulesets/groovyism.xml - style preferences
    // ❌ NO rulesets/convention.xml - style preferences
    //
    // Rationale:
    // - Jenkinsfiles are DSL scripts with unique formatting needs
    // - Style violations should be auto-fixed via format-on-save, not flagged
    // - Reduce ~100 rules to ~10 rules, focusing on actual pipeline safety
}

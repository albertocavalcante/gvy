/**
 * Jenkins pipeline ruleset - CPS safety rules.
 *
 * This ruleset includes CodeNarc's bundled Jenkins CPS rules that detect
 * common runtime errors in Jenkins pipelines and shared libraries.
 *
 * See: https://codenarc.org/codenarc-rules-jenkins.html
 */
ruleset {
    description 'Jenkins pipeline ruleset - CPS safety rules'

    // Include all bundled Jenkins CPS rules from CodeNarc
    // These rules detect CPS transformation issues:
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
        }
    }

    // Basic rules (excluding patterns common in Jenkinsfiles)
    ruleset('rulesets/basic.xml') {
        exclude 'EmptyCatchBlock'  // Common pattern in Jenkinsfiles for error handling
    }

    // Import rules
    ruleset('rulesets/imports.xml')

    // Formatting rules
    ruleset('rulesets/formatting.xml') {
        exclude 'LineLength'  // Jenkinsfiles often have long lines
    }
}

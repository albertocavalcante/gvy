/*
 * Default CodeNarc ruleset for general Groovy projects.
 * Provides a balanced set of rules that catch common issues without being overly strict.
 */
ruleset {
    description 'Groovy LSP Default Rules for General Projects'

    // Core quality rules
    ruleset('rulesets/basic.xml') {
        // Exclude overly strict instantiation rules
        exclude 'ExplicitHashSetInstantiation'
        exclude 'ExplicitArrayListInstantiation'
        exclude 'ExplicitLinkedListInstantiation'
        exclude 'ExplicitCallToAndMethod'
        exclude 'ExplicitCallToOrMethod'
    }

    // Import organization
    ruleset('rulesets/imports.xml') {
        exclude 'MissingBlankLineAfterImports'
        exclude 'NoWildcardImports'  // Common in Groovy
    }

    // Unused code detection
    ruleset('rulesets/unused.xml')

    // Exception handling
    ruleset('rulesets/exceptions.xml') {
        exclude 'CatchException'  // Common in scripts
        exclude 'CatchThrowable'
        exclude 'ThrowException'  // Sometimes necessary
    }

    // Formatting (basic subset)
    ruleset('rulesets/formatting.xml') {
        include 'TrailingWhitespace'
        exclude 'ConsecutiveBlankLines'  // Configure separately
    }

    // Configured formatting rules
    ConsecutiveBlankLines {}
    // Note: ConsecutiveBlankLines rule has no configurable properties for max lines

    // Naming conventions (exclude from bulk import, configure separately)
    ruleset('rulesets/naming.xml') {
        exclude 'MethodName'
        exclude 'VariableName'
        exclude 'FieldName'
    }

    // Configured naming rules
    MethodName {
        regex = /^[a-z][a-zA-Z0-9_]*$/
    }

    VariableName {
        regex = /^[a-z][a-zA-Z0-9_]*$/
    }

    FieldName {
        regex = /^[a-z][a-zA-Z0-9_]*$/
    }

    // Size and complexity (exclude from bulk import, configure separately)
    ruleset('rulesets/size.xml') {
        exclude 'MethodSize'
        exclude 'ClassSize'
    }

    // Configured size rules
    MethodSize {
        maxLines = 100
    }

    ClassSize {
        maxLines = 500
    }

    // Concurrency (important for correctness)
    ruleset('rulesets/concurrency.xml')

    // Design rules (subset)
    ruleset('rulesets/design.xml') {
        include 'PublicInstanceField'
        include 'BuilderMethodWithSideEffects'
    }
}

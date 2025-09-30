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
    rule('TrailingWhitespace')
    rule('ConsecutiveBlankLines') {
        length = 3  // Allow up to 2 blank lines
    }

    // Naming conventions
    rule('MethodName') {
        regex = /^[a-z][a-zA-Z0-9_]*$/
    }

    rule('VariableName') {
        regex = /^[a-z][a-zA-Z0-9_]*$/
    }

    rule('FieldName') {
        regex = /^[a-z][a-zA-Z0-9_]*$/
    }

    // Size and complexity (reasonable limits)
    rule('MethodSize') {
        maxLines = 100
    }

    rule('ClassSize') {
        maxLines = 500
    }

    // Concurrency (important for correctness)
    ruleset('rulesets/concurrency.xml')

    // Design rules (subset)
    rule('PublicInstanceField')
    rule('BuilderMethodWithSideEffects')
}

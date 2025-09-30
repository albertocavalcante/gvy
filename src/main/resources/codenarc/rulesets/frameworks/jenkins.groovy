/*
 * Specialized CodeNarc ruleset for Jenkins projects.
 * More lenient with DSL patterns and script-like code.
 */
ruleset {
    description 'Groovy LSP Rules for Jenkins Projects'

    // Basic quality rules (relaxed)
    ruleset('rulesets/basic.xml') {
        // Exclude rules that conflict with Jenkins DSL patterns
        exclude 'ExplicitHashSetInstantiation'
        exclude 'ExplicitArrayListInstantiation'
        exclude 'ExplicitLinkedListInstantiation'
        exclude 'ExplicitCallToAndMethod'
        exclude 'ExplicitCallToOrMethod'
        exclude 'HardCodedWindowsFileSeparator'  // May be intentional
        exclude 'HardCodedWindowsRootDirectory'
    }

    // Import rules (very relaxed for Jenkins)
    ruleset('rulesets/imports.xml') {
        exclude 'MissingBlankLineAfterImports'
        exclude 'NoWildcardImports'
        exclude 'UnnecessaryGroovyImport'  // Sometimes needed for clarity
    }

    // Unused code (but allow Jenkins implicit variables)
    ruleset('rulesets/unused.xml') {
        exclude 'UnusedVariable'  // We'll configure this separately
    }

    // Configure UnusedVariable rule separately with Jenkins-specific exclusions
    UnusedVariable {
        // Jenkins provides these variables implicitly
        ignoreVariableNames = 'env,params,currentBuild,BUILD_NUMBER,JOB_NAME,WORKSPACE,NODE_NAME,scm'
    }

    // Exception handling (very lenient for scripts)
    ruleset('rulesets/exceptions.xml') {
        include 'CatchArrayIndexOutOfBoundsException'
        include 'CatchNullPointerException'
    }

    // Formatting (minimal)
    ruleset('rulesets/formatting.xml') {
        include 'TrailingWhitespace'
    }

    // Naming (Jenkins-aware)
    MethodName {
        // Allow Jenkins DSL method patterns and step names
        regex = /^[a-z][a-zA-Z0-9_]*$|^call$|^pipeline$|^agent$|^stages$|^stage$|^steps$|^sh$|^bat$|^script$|^node$|^build$|^checkout$|^git$|^parallel$/
    }

    VariableName {
        // More lenient for Jenkins variables
        regex = /^[a-z][a-zA-Z0-9_]*$|^[A-Z][A-Z0-9_]*$/
        // Allow common Jenkins variable patterns
        ignoreVariableNames = 'env,params,currentBuild,BUILD_NUMBER,JOB_NAME,WORKSPACE,NODE_NAME,BRANCH_NAME,CHANGE_ID'
    }

    // Size limits (more generous for pipeline scripts)
    MethodSize {
        maxLines = 150
    }

    ClassSize {
        maxLines = 1000
    }

    // Jenkins-specific rules (using only rules that actually exist)
    // Note: Some rules don't exist in current CodeNarc version, so we exclude them from rulesets instead

    // Exclude rules that would conflict with Jenkins DSL patterns
    ruleset('rulesets/groovyism.xml') {
        exclude 'ExplicitCallToGetAtMethod'
        exclude 'ExplicitCallToPutAtMethod'
    }

    // Basic formatting and conventions (very minimal for scripts)
    ruleset('rulesets/convention.xml') {
        exclude 'CompileStatic'  // Not required for Jenkins scripts
        exclude 'NoDef'          // Jenkins scripts commonly use 'def'
    }
}

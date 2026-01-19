/**
 * Default CodeNarc ruleset for plain Groovy projects.
 * This provides a sensible baseline of rules for code quality.
 */
ruleset {
    description 'Default ruleset for Groovy LSP'

    // Basic rules for common errors
    ruleset('rulesets/basic.xml')

    // Import rules for clean imports
    ruleset('rulesets/imports.xml') {
        // Configure priorities for import rules
        DuplicateImport {
            priority = 3
        }
        UnnecessaryGroovyImport {
            priority = 3
        }
    }

    // Unnecessary code
    ruleset('rulesets/unnecessary.xml') {
        // Exclude rules we want to disable
        exclude 'UnnecessaryGetter'
        exclude 'UnnecessarySetter'

        // Configure priorities
        UnnecessaryPublicModifier {
            priority = 3
        }
        UnnecessarySemicolon {
            priority = 3
        }
    }

    // Unused code
    ruleset('rulesets/unused.xml') {
        UnusedVariable {
            priority = 2
        }
        UnusedPrivateField {
            priority = 2
        }
        UnusedPrivateMethod {
            priority = 2
        }
    }

    // Formatting rules
    ruleset('rulesets/formatting.xml') {
        // Exclude line length (too noisy)
        exclude 'LineLength'

        // Configure priorities
        SpaceAroundClosureArrow {
            enabled = true
        }
        TrailingWhitespace {
            priority = 3
        }
    }

    // Groovyism - idiomatic Groovy
    ruleset('rulesets/groovyism.xml') {
        GStringExpressionWithinString {
            priority = 3
        }
        ExplicitCallToEqualsMethod {
            priority = 3
        }
    }

    // Exception handling
    ruleset('rulesets/exceptions.xml') {
        CatchException {
            priority = 2
        }
        CatchThrowable {
            priority = 1
        }
        ThrowException {
            priority = 2
        }
    }
}

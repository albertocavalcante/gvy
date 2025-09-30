package com.github.albertocavalcante.groovylsp.jenkins

import groovy.transform.CompileStatic

/**
 * Simple test class to verify Groovy-Kotlin interop is working.
 * This demonstrates that Kotlin can call Groovy code.
 */
@CompileStatic
class SimpleGdslParser {

    /**
     * Parses a simple GDSL-like string and returns method names.
     */
    static List<String> parseMethodNames(String input) {
        if (!input) return []

        // Simple pattern matching for method definitions
        List<String> methods = []
        input.eachLine { line ->
            def trimmed = line.trim()
            if (trimmed.startsWith("method(")) {
                // Extract method name from method(name: 'name', ...)
                def matcher = trimmed =~ /method\(\s*name:\s*['"]([^'"]+)['"]/
                if (matcher.find()) {
                    methods.add(matcher.group(1))
                }
            }
        }
        return methods
    }

    /**
     * Validates if a string looks like valid GDSL.
     */
    static boolean isValidGdsl(String input) {
        if (!input) return false
        return input.contains("method(") || input.contains("contribute(")
    }

    /**
     * Returns a test result to verify the interop.
     */
    static Map<String, Object> getTestResult() {
        return [
            message: "Groovy-Kotlin interop is working!",
            timestamp: System.currentTimeMillis(),
            parser: "SimpleGdslParser",
            version: "1.0"
        ]
    }
}

package org.example

/**
 * Helper class for build operations.
 * Used by vars/buildPlugin.groovy to demonstrate src/ imports.
 */
class BuildHelper {

    /**
     * Execute the main build process.
     */
    void runBuild() {
        println 'Running Maven build...'
    }

    /**
     * Execute test suite.
     */
    void runTests() {
        println 'Running test suite...'
    }

    /**
     * Publish artifacts to repository.
     * @param repository Target repository URL
     */
    void publishArtifacts(String repository) {
        println "Publishing to ${repository}"
    }
}

package com.github.albertocavalcante.gvy.build

/**
 * Utility for detecting the test execution environment.
 *
 * TODO: Replace assumeFalse(isRunningInBazel) with JUnit @Tag("integration") or
 *       @Tag("requires-network") for better test categorization. This would allow:
 *       - Explicit test filtering in both Bazel and Gradle
 *       - Running integration tests locally when needed
 *       - Clear documentation of test requirements
 */
object TestEnvironment {

    /**
     * Returns true if tests are running under Bazel's sandbox.
     * Integration tests that invoke external tools (Gradle, Maven)
     * should be skipped in Bazel since the sandbox doesn't provide
     * access to these build tools.
     */
    val isRunningInBazel: Boolean by lazy {
        val testSrcDir = System.getenv("TEST_SRCDIR")
        val runfilesDir = System.getenv("RUNFILES_DIR")
        val buildWorkspace = System.getenv("BUILD_WORKSPACE_DIRECTORY")
        val isBazel = testSrcDir != null || runfilesDir != null || buildWorkspace != null
        if (isBazel) {
            println("TestEnvironment: Detected Bazel (TEST_SRCDIR=$testSrcDir, RUNFILES_DIR=$runfilesDir)")
        }
        isBazel
    }
}

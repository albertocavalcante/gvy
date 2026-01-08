package com.github.albertocavalcante.groovylsp.providers.testing

/**
 * Parameters for the `groovy/discoverTests` LSP request.
 */
data class DiscoverTestsParams(val workspaceUri: String)

/**
 * Represents a test suite (Spock specification class).
 *
 * @property uri File URI (e.g., "file:///path/to/MySpec.groovy")
 * @property suite Fully qualified class name (e.g., "com.example.MySpec")
 * @property tests List of test methods in this suite
 */
data class TestSuite(val uri: String, val suite: String, val tests: List<Test>)

/**
 * Represents a single test method.
 *
 * @property test Method name (e.g., "should calculate sum")
 * @property line 1-indexed line number where the test method starts
 */
data class Test(val test: String, val line: Int)

/**
 * Parameters for the `groovy/runTest` LSP request.
 *
 * @property uri File URI of the test class
 * @property suite Fully qualified class name
 * @property test Optional method name (null = run all tests in suite)
 * @property debug Whether to return a debug command
 */
data class RunTestParams(val uri: String, val suite: String, val test: String? = null, val debug: Boolean = false)

/**
 * Parameters for the `groovy/getBuildToolInfo` LSP request.
 */
data class GetBuildToolInfoParams(val workspaceUri: String)

/**
 * Build tool information returned by `groovy/getBuildToolInfo`.
 *
 * @property name Build tool name: "gradle", "maven", "bsp", or "unknown"
 * @property detected Whether a build tool was detected
 * @property supportsTestExecution Whether the build tool supports test execution via `groovy/runTest`
 * @property supportsDebug Whether the build tool supports debug mode
 * @property supportsCoverage Whether the build tool supports coverage
 */
data class BuildToolInfo(
    val name: String,
    val detected: Boolean,
    val supportsTestExecution: Boolean = false,
    val supportsDebug: Boolean = false,
    val supportsCoverage: Boolean = false,
)

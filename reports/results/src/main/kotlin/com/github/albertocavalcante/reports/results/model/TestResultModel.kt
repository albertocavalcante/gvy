package com.github.albertocavalcante.reports.results.model

/**
 * Status of a test execution.
 */
enum class TestResultStatus {
    SUCCESS,
    FAILURE,
    SKIPPED,
    ERROR,
}

/**
 * Result of a single test execution.
 *
 * @property testId Fully qualified test identifier (e.g., "com.example.MySpec.testName")
 * @property name Human-readable test name
 * @property status Test execution result
 * @property durationMs Test duration in milliseconds
 * @property output Captured stdout/stderr (from &lt;system-out&gt; in Surefire XML)
 * @property failureMessage Error message if the test failed
 * @property stackTrace Stack trace if the test failed
 * @property className Class name containing the test
 */
data class TestResultItem(
    val testId: String,
    val name: String,
    val status: TestResultStatus,
    val durationMs: Long = 0,
    val output: String? = null,
    val failureMessage: String? = null,
    val stackTrace: String? = null,
    val className: String? = null,
)

/**
 * Summary of test execution.
 *
 * @property total Total number of tests
 * @property passed Number of passing tests
 * @property failed Number of failing tests
 * @property skipped Number of skipped tests
 * @property errors Number of tests with errors
 * @property durationMs Total duration in milliseconds
 */
data class TestResultSummary(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val errors: Int,
    val durationMs: Long,
)

/**
 * Response for the `groovy/getTestResults` LSP request.
 *
 * @property results List of individual test results
 * @property summary Aggregate summary of test execution
 */
data class TestResultsResponse(val results: List<TestResultItem>, val summary: TestResultSummary)

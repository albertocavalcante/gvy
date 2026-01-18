package com.github.albertocavalcante.reports.results.parsers

import com.github.albertocavalcante.reports.results.model.TestResultItem
import com.github.albertocavalcante.reports.results.model.TestResultsResponse
import java.io.File

/**
 * Interface for parsing test result reports.
 *
 * Implementations should handle specific test report formats (e.g., Surefire XML, JUnit XML).
 */
interface TestResultParser {
    /**
     * Parse all test reports in a workspace.
     *
     * @param workspaceRoot Root directory of the workspace
     * @return Aggregated test results from all modules
     */
    fun parseWorkspace(workspaceRoot: File): TestResultsResponse

    /**
     * Parse a single test report file.
     *
     * @param file Test report file to parse
     * @return List of individual test results from the file
     */
    fun parseReportFile(file: File): List<TestResultItem>
}

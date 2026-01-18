package com.github.albertocavalcante.reports.coverage.parsers

import com.github.albertocavalcante.reports.coverage.model.CoverageResponse
import com.github.albertocavalcante.reports.coverage.model.FileCoverageData
import java.io.File

/**
 * Interface for parsing coverage reports.
 *
 * Implementations should support specific coverage report formats (JaCoCo, Cobertura, LCOV, etc.).
 */
interface CoverageParser {
    /**
     * Parse all coverage reports in a workspace.
     *
     * @param workspaceRoot Root directory of the workspace
     * @return Aggregated coverage data from all found reports
     */
    fun parseWorkspace(workspaceRoot: File): CoverageResponse

    /**
     * Parse a single coverage report file.
     *
     * @param file The coverage report file
     * @param workspaceRoot Workspace root for resolving file URIs
     * @return List of file coverage data from the report
     */
    fun parseReportFile(file: File, workspaceRoot: File): List<FileCoverageData>
}

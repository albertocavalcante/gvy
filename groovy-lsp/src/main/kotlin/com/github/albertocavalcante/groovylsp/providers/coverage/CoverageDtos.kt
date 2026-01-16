package com.github.albertocavalcante.groovylsp.providers.coverage

/**
 * Parameters for the `groovy/getCoverage` LSP request.
 *
 * @property workspaceUri URI of the workspace root
 */
data class GetCoverageParams(val workspaceUri: String)

/**
 * Branch coverage information for a line.
 *
 * @property covered Number of covered branches
 * @property total Total number of branches
 */
data class BranchInfo(val covered: Int, val total: Int)

/**
 * Line coverage information.
 *
 * @property line 1-indexed line number
 * @property covered Whether the line was executed
 * @property hitCount Number of times the line was executed (null if not available)
 * @property branchInfo Branch coverage for this line (null if no branches)
 */
data class LineCoverage(
    val line: Int,
    val covered: Boolean,
    val hitCount: Int? = null,
    val branchInfo: BranchInfo? = null,
)

/**
 * Coverage summary for a single file.
 *
 * @property linesCovered Number of covered lines
 * @property linesTotal Total number of lines
 * @property branchesCovered Number of covered branches
 * @property branchesTotal Total number of branches
 */
data class FileCoverageSummary(
    val linesCovered: Int,
    val linesTotal: Int,
    val branchesCovered: Int,
    val branchesTotal: Int,
)

/**
 * Coverage data for a single file.
 *
 * @property uri File URI (e.g., "file:///path/to/MyClass.groovy")
 * @property lines Per-line coverage information
 * @property summary Aggregate coverage summary for this file
 */
data class FileCoverageData(val uri: String, val lines: List<LineCoverage>, val summary: FileCoverageSummary)

/**
 * Overall coverage summary.
 *
 * @property lineCoveragePercent Overall line coverage percentage (0-100)
 * @property branchCoveragePercent Overall branch coverage percentage (0-100)
 * @property linesCovered Total covered lines across all files
 * @property linesTotal Total lines across all files
 * @property branchesCovered Total covered branches across all files
 * @property branchesTotal Total branches across all files
 */
data class CoverageSummary(
    val lineCoveragePercent: Double,
    val branchCoveragePercent: Double,
    val linesCovered: Int,
    val linesTotal: Int,
    val branchesCovered: Int,
    val branchesTotal: Int,
)

/**
 * Response for the `groovy/getCoverage` LSP request.
 *
 * @property files Per-file coverage data
 * @property summary Overall coverage summary
 */
data class CoverageResponse(val files: List<FileCoverageData>, val summary: CoverageSummary)

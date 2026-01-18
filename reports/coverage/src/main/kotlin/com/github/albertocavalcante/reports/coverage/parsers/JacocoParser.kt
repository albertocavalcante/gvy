package com.github.albertocavalcante.reports.coverage.parsers

import com.github.albertocavalcante.reports.coverage.model.BranchInfo
import com.github.albertocavalcante.reports.coverage.model.CoverageResponse
import com.github.albertocavalcante.reports.coverage.model.CoverageSummary
import com.github.albertocavalcante.reports.coverage.model.FileCoverageData
import com.github.albertocavalcante.reports.coverage.model.FileCoverageSummary
import com.github.albertocavalcante.reports.coverage.model.LineCoverage
import com.github.albertocavalcante.reports.discovery.ProjectDiscovery
import com.github.albertocavalcante.reports.xml.XmlUtils
import io.github.oshai.kotlinlogging.KotlinLogging
import org.w3c.dom.Element
import java.io.File
import java.net.URI

/**
 * Parses JaCoCo XML coverage reports.
 *
 * Handles both Gradle and Maven projects, including multi-module Maven aggregated reports.
 *
 * JaCoCo XML format:
 * ```xml
 * <report name="project">
 *   <package name="com/example">
 *     <sourcefile name="MyClass.groovy">
 *       <line nr="10" mi="0" ci="3" mb="0" cb="0"/>
 *       <!-- mi=missed instructions, ci=covered instructions, mb=missed branches, cb=covered branches -->
 *     </sourcefile>
 *   </package>
 * </report>
 * ```
 */
@Suppress("TooManyFunctions") // Cohesive coverage parsing
object JacocoParser : CoverageParser {
    private val logger = KotlinLogging.logger {}

    /**
     * Known JaCoCo report locations.
     */
    private val JACOCO_REPORT_PATHS = listOf(
        // Gradle
        "build/reports/jacoco/test/jacocoTestReport.xml",
        // Maven single module
        "target/site/jacoco/jacoco.xml",
        // Maven multi-module aggregated
        "target/site/jacoco-aggregate/jacoco.xml",
    )

    /**
     * Parse all JaCoCo reports in a workspace.
     *
     * @param workspaceRoot Root directory of the workspace
     * @return Aggregated coverage data from all found reports
     */
    override fun parseWorkspace(workspaceRoot: File): CoverageResponse {
        val reportFiles = discoverJacocoReports(workspaceRoot)
        logger.info { "Found ${reportFiles.size} JaCoCo report files" }

        if (reportFiles.isEmpty()) {
            return emptyCoverageResponse()
        }

        val allFileData = mutableMapOf<String, FileCoverageData>()

        for (reportFile in reportFiles) {
            try {
                val fileCoverageList = parseReportFile(reportFile, workspaceRoot)
                for (fileData in fileCoverageList) {
                    // Merge if we already have data for this file (from another module)
                    val existing = allFileData[fileData.uri]
                    if (existing != null) {
                        allFileData[fileData.uri] = mergeFileCoverage(existing, fileData)
                    } else {
                        allFileData[fileData.uri] = fileData
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse JaCoCo report: ${reportFile.absolutePath}" }
            }
        }

        val fileList = allFileData.values.toList()
        return CoverageResponse(
            files = fileList,
            summary = computeSummary(fileList),
        )
    }

    /**
     * Discover all JaCoCo report files in the workspace.
     */
    private fun discoverJacocoReports(workspaceRoot: File): List<File> {
        val reports = mutableListOf<File>()

        // Check standard locations in root
        for (path in JACOCO_REPORT_PATHS) {
            val file = File(workspaceRoot, path)
            if (file.exists() && file.isFile) {
                reports.add(file)
            }
        }

        // Check Maven submodules
        val submodules = ProjectDiscovery.discoverMavenModules(workspaceRoot)
        for (module in submodules) {
            val moduleDir = File(workspaceRoot, module)
            for (path in JACOCO_REPORT_PATHS) {
                val file = File(moduleDir, path)
                if (file.exists() && file.isFile) {
                    reports.add(file)
                }
            }
        }

        // Also check Gradle subprojects (look for build directories)
        val gradleSubprojects = ProjectDiscovery.findGradleSubprojects(workspaceRoot)
        for (subproject in gradleSubprojects) {
            for (path in JACOCO_REPORT_PATHS) {
                val file = File(subproject, path)
                if (file.exists() && file.isFile) {
                    reports.add(file)
                }
            }
        }

        return reports.distinct()
    }

    /**
     * Parse a single JaCoCo XML report file.
     *
     * @param file The JaCoCo XML report file
     * @param workspaceRoot Workspace root for resolving file URIs
     */
    override fun parseReportFile(file: File, workspaceRoot: File): List<FileCoverageData> {
        val dBuilder = XmlUtils.createSecureDocumentBuilder()
        val doc = dBuilder.parse(file)
        doc.documentElement.normalize()

        val results = mutableListOf<FileCoverageData>()
        val packages = doc.getElementsByTagName("package")

        for (i in 0 until packages.length) {
            val pkg = packages.item(i) as? Element ?: continue
            val packageName = pkg.getAttribute("name") // e.g., "com/example"

            val sourceFiles = pkg.getElementsByTagName("sourcefile")
            for (j in 0 until sourceFiles.length) {
                val sourceFile = sourceFiles.item(j) as? Element ?: continue
                val result = parseSourceFile(sourceFile, packageName, workspaceRoot)
                if (result != null) {
                    results.add(result)
                }
            }
        }

        return results
    }

    /**
     * Parse a single sourcefile element.
     */
    private fun parseSourceFile(sourceFile: Element, packageName: String, workspaceRoot: File): FileCoverageData? {
        val fileName = sourceFile.getAttribute("name") // e.g., "MyClass.groovy"
        if (fileName.isEmpty()) return null

        val lines = mutableListOf<LineCoverage>()
        val lineElements = sourceFile.getElementsByTagName("line")

        for (i in 0 until lineElements.length) {
            val lineEl = lineElements.item(i) as? Element ?: continue
            val lineCoverage = parseLineElement(lineEl)
            if (lineCoverage != null) {
                lines.add(lineCoverage)
            }
        }

        if (lines.isEmpty()) return null

        // Resolve file URI
        val uri = resolveFileUri(packageName, fileName, workspaceRoot)

        // Compute summary
        val summary = computeFileSummary(lines)

        return FileCoverageData(
            uri = uri,
            lines = lines.sortedBy { it.line },
            summary = summary,
        )
    }

    /**
     * Parse a single line element.
     */
    private fun parseLineElement(lineEl: Element): LineCoverage? {
        val lineNum = lineEl.getAttribute("nr").toIntOrNull() ?: return null

        // ci = covered instructions (mi = missed instructions, not used for coverage status)
        val ci = lineEl.getAttribute("ci").toIntOrNull() ?: 0

        // mb = missed branches, cb = covered branches
        val mb = lineEl.getAttribute("mb").toIntOrNull() ?: 0
        val cb = lineEl.getAttribute("cb").toIntOrNull() ?: 0

        val covered = ci > 0
        val hitCount = if (covered) ci else 0

        val branchInfo = if (mb > 0 || cb > 0) {
            BranchInfo(covered = cb, total = mb + cb)
        } else {
            null
        }

        return LineCoverage(
            line = lineNum,
            covered = covered,
            hitCount = hitCount,
            branchInfo = branchInfo,
        )
    }

    /**
     * Resolve a source file to a URI.
     * Searches common source directories for the file.
     */
    private fun resolveFileUri(packageName: String, fileName: String, workspaceRoot: File): String {
        val packagePath = packageName.replace('/', File.separatorChar)

        // Common source directories to search
        val sourceDirs = listOf(
            "src/main/groovy",
            "src/main/java",
            "src/test/groovy",
            "src/test/java",
            "vars",
            "src",
        )

        for (sourceDir in sourceDirs) {
            val candidate = File(workspaceRoot, "$sourceDir/$packagePath/$fileName")
            if (candidate.exists()) {
                return candidate.toURI().toString()
            }
        }

        // Also check Maven submodules
        val submodules = ProjectDiscovery.discoverMavenModules(workspaceRoot)
        for (module in submodules) {
            for (sourceDir in sourceDirs) {
                val candidate = File(workspaceRoot, "$module/$sourceDir/$packagePath/$fileName")
                if (candidate.exists()) {
                    return candidate.toURI().toString()
                }
            }
        }

        // Fallback: return a constructed path (file may not exist yet)
        return URI("file", null, "/${packagePath.replace('\\', '/')}/$fileName", null).toString()
    }

    /**
     * Compute summary for a single file.
     */
    private fun computeFileSummary(lines: List<LineCoverage>): FileCoverageSummary {
        var linesCovered = 0
        var branchesCovered = 0
        var branchesTotal = 0

        for (line in lines) {
            if (line.covered) linesCovered++
            line.branchInfo?.let {
                branchesCovered += it.covered
                branchesTotal += it.total
            }
        }

        return FileCoverageSummary(
            linesCovered = linesCovered,
            linesTotal = lines.size,
            branchesCovered = branchesCovered,
            branchesTotal = branchesTotal,
        )
    }

    /**
     * Merge two FileCoverageData objects (from different reports covering same file).
     */
    private fun mergeFileCoverage(a: FileCoverageData, b: FileCoverageData): FileCoverageData {
        val lineMap = mutableMapOf<Int, LineCoverage>()

        // Add all lines from a
        for (line in a.lines) {
            lineMap[line.line] = line
        }

        // Merge lines from b (take max hit count, merge branches)
        for (line in b.lines) {
            val existing = lineMap[line.line]
            if (existing != null) {
                lineMap[line.line] = LineCoverage(
                    line = line.line,
                    covered = existing.covered || line.covered,
                    hitCount = maxOf(existing.hitCount ?: 0, line.hitCount ?: 0),
                    branchInfo = mergeBranchInfo(existing.branchInfo, line.branchInfo),
                )
            } else {
                lineMap[line.line] = line
            }
        }

        val mergedLines = lineMap.values.sortedBy { it.line }
        return FileCoverageData(
            uri = a.uri,
            lines = mergedLines,
            summary = computeFileSummary(mergedLines),
        )
    }

    /**
     * Merge branch info from two sources.
     */
    private fun mergeBranchInfo(a: BranchInfo?, b: BranchInfo?): BranchInfo? {
        if (a == null) return b
        if (b == null) return a
        return BranchInfo(
            covered = maxOf(a.covered, b.covered),
            total = maxOf(a.total, b.total),
        )
    }

    /**
     * Compute overall summary from all files.
     */
    private fun computeSummary(files: List<FileCoverageData>): CoverageSummary {
        var linesCovered = 0
        var linesTotal = 0
        var branchesCovered = 0
        var branchesTotal = 0

        for (file in files) {
            linesCovered += file.summary.linesCovered
            linesTotal += file.summary.linesTotal
            branchesCovered += file.summary.branchesCovered
            branchesTotal += file.summary.branchesTotal
        }

        val lineCoveragePercent = if (linesTotal > 0) {
            (linesCovered.toDouble() / linesTotal) * 100.0
        } else {
            0.0
        }

        val branchCoveragePercent = if (branchesTotal > 0) {
            (branchesCovered.toDouble() / branchesTotal) * 100.0
        } else {
            0.0
        }

        return CoverageSummary(
            lineCoveragePercent = lineCoveragePercent,
            branchCoveragePercent = branchCoveragePercent,
            linesCovered = linesCovered,
            linesTotal = linesTotal,
            branchesCovered = branchesCovered,
            branchesTotal = branchesTotal,
        )
    }

    /**
     * Return an empty coverage response.
     */
    private fun emptyCoverageResponse(): CoverageResponse = CoverageResponse(
        files = emptyList(),
        summary = CoverageSummary(
            lineCoveragePercent = 0.0,
            branchCoveragePercent = 0.0,
            linesCovered = 0,
            linesTotal = 0,
            branchesCovered = 0,
            branchesTotal = 0,
        ),
    )
}

package com.github.albertocavalcante.groovylsp.providers.testing.parsers

import com.github.albertocavalcante.groovylsp.providers.testing.TestResultItem
import com.github.albertocavalcante.groovylsp.providers.testing.TestResultStatus
import com.github.albertocavalcante.groovylsp.providers.testing.TestResultSummary
import com.github.albertocavalcante.groovylsp.providers.testing.TestResultsResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToLong

/**
 * Parses Surefire/Failsafe XML test reports.
 *
 * Handles multi-module Maven projects by discovering all surefire-reports directories.
 *
 * Surefire XML format:
 * ```xml
 * <testsuite name="com.example.MySpec" time="4.662" tests="1" errors="0" skipped="0" failures="0">
 *   <testcase name="testName" classname="com.example.MySpec" time="0.123">
 *     <system-out><![CDATA[output here]]></system-out>
 *   </testcase>
 * </testsuite>
 * ```
 */
object SurefireXmlParser {
    private val logger = KotlinLogging.logger {}

    /**
     * Parse all Surefire reports in a workspace.
     *
     * @param workspaceRoot Root directory of the workspace
     * @return Aggregated test results from all modules
     */
    fun parseWorkspace(workspaceRoot: File): TestResultsResponse {
        val reportDirs = discoverSurefireReportDirs(workspaceRoot)
        logger.info { "Found ${reportDirs.size} surefire-reports directories" }

        val allResults = mutableListOf<TestResultItem>()

        for (reportDir in reportDirs) {
            val xmlFiles = reportDir.listFiles { file -> file.name.startsWith("TEST-") && file.extension == "xml" }
                ?: continue

            for (xmlFile in xmlFiles) {
                try {
                    val results = parseReportFile(xmlFile)
                    allResults.addAll(results)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Surefire report: ${xmlFile.absolutePath}" }
                }
            }
        }

        return TestResultsResponse(
            results = allResults,
            summary = computeSummary(allResults),
        )
    }

    /**
     * Discover all surefire-reports directories in a multi-module project.
     */
    private fun discoverSurefireReportDirs(workspaceRoot: File): List<File> {
        val reportDirs = mutableListOf<File>()

        // Check root module
        val rootReportDir = File(workspaceRoot, "target/surefire-reports")
        if (rootReportDir.isDirectory) {
            reportDirs.add(rootReportDir)
        }

        // Also check for failsafe reports (integration tests)
        val rootFailsafeDir = File(workspaceRoot, "target/failsafe-reports")
        if (rootFailsafeDir.isDirectory) {
            reportDirs.add(rootFailsafeDir)
        }

        // Discover submodules from pom.xml
        val submodules = discoverMavenModules(workspaceRoot)
        for (module in submodules) {
            val moduleDir = File(workspaceRoot, module)

            val surefireDir = File(moduleDir, "target/surefire-reports")
            if (surefireDir.isDirectory) {
                reportDirs.add(surefireDir)
            }

            val failsafeDir = File(moduleDir, "target/failsafe-reports")
            if (failsafeDir.isDirectory) {
                reportDirs.add(failsafeDir)
            }
        }

        return reportDirs
    }

    /**
     * Discover Maven submodules from pom.xml.
     */
    private fun discoverMavenModules(workspaceRoot: File): List<String> {
        val pomFile = File(workspaceRoot, "pom.xml")
        if (!pomFile.exists()) return emptyList()

        return try {
            val dbFactory = DocumentBuilderFactory.newInstance()
            // Disable external entities for security
            dbFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val dBuilder = dbFactory.newDocumentBuilder()
            val doc = dBuilder.parse(pomFile)
            doc.documentElement.normalize()

            val moduleNodes = doc.getElementsByTagName("module")
            (0 until moduleNodes.length).mapNotNull { i ->
                moduleNodes.item(i).textContent?.trim()?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse pom.xml for modules" }
            emptyList()
        }
    }

    /**
     * Parse a single Surefire XML report file.
     */
    fun parseReportFile(file: File): List<TestResultItem> {
        val dbFactory = DocumentBuilderFactory.newInstance()
        // Disable external entities for security
        dbFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(file)
        doc.documentElement.normalize()

        val results = mutableListOf<TestResultItem>()
        val testCases = doc.getElementsByTagName("testcase")

        for (i in 0 until testCases.length) {
            val testCase = testCases.item(i) as? Element ?: continue
            val result = parseTestCase(testCase)
            results.add(result)
        }

        return results
    }

    /**
     * Parse a single testcase element.
     */
    private fun parseTestCase(testCase: Element): TestResultItem {
        val name = testCase.getAttribute("name")
        val className = testCase.getAttribute("classname")
        val timeStr = testCase.getAttribute("time")
        val durationMs = parseTimeToMs(timeStr)

        // Check for failure/error/skipped child elements
        val (status, failureMessage, stackTrace) = parseTestStatus(testCase)

        // Extract system-out content
        val output = extractChildText(testCase, "system-out")

        val testId = if (className.isNotEmpty()) "$className.$name" else name

        return TestResultItem(
            testId = testId,
            name = name,
            className = className.takeIf { it.isNotEmpty() },
            status = status,
            durationMs = durationMs,
            output = output,
            failureMessage = failureMessage,
            stackTrace = stackTrace,
        )
    }

    /**
     * Parse test status from child elements.
     */
    private fun parseTestStatus(testCase: Element): Triple<TestResultStatus, String?, String?> {
        // Check for failure
        val failures = testCase.getElementsByTagName("failure")
        if (failures.length > 0) {
            val failure = failures.item(0) as Element
            val message = failure.getAttribute("message").takeIf { it.isNotEmpty() }
            val stackTrace = failure.textContent?.trim()?.takeIf { it.isNotEmpty() }
            return Triple(TestResultStatus.FAILURE, message, stackTrace)
        }

        // Check for error
        val errors = testCase.getElementsByTagName("error")
        if (errors.length > 0) {
            val error = errors.item(0) as Element
            val message = error.getAttribute("message").takeIf { it.isNotEmpty() }
            val stackTrace = error.textContent?.trim()?.takeIf { it.isNotEmpty() }
            return Triple(TestResultStatus.ERROR, message, stackTrace)
        }

        // Check for skipped
        val skipped = testCase.getElementsByTagName("skipped")
        if (skipped.length > 0) {
            val skippedEl = skipped.item(0) as Element
            val message = skippedEl.getAttribute("message").takeIf { it.isNotEmpty() }
            return Triple(TestResultStatus.SKIPPED, message, null)
        }

        return Triple(TestResultStatus.SUCCESS, null, null)
    }

    /**
     * Extract text content from a child element.
     */
    private fun extractChildText(parent: Element, tagName: String): String? {
        val elements = parent.getElementsByTagName(tagName)
        if (elements.length == 0) return null

        return elements.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Parse Surefire time attribute (seconds as decimal) to milliseconds.
     */
    private fun parseTimeToMs(timeStr: String): Long = try {
        (timeStr.toDouble() * 1000).roundToLong()
    } catch (e: NumberFormatException) {
        0L
    }

    /**
     * Compute summary from list of results.
     */
    private fun computeSummary(results: List<TestResultItem>): TestResultSummary {
        var passed = 0
        var failed = 0
        var skipped = 0
        var errors = 0
        var totalDuration = 0L

        for (result in results) {
            totalDuration += result.durationMs
            when (result.status) {
                TestResultStatus.SUCCESS -> passed++
                TestResultStatus.FAILURE -> failed++
                TestResultStatus.SKIPPED -> skipped++
                TestResultStatus.ERROR -> errors++
            }
        }

        return TestResultSummary(
            total = results.size,
            passed = passed,
            failed = failed,
            skipped = skipped,
            errors = errors,
            durationMs = totalDuration,
        )
    }
}

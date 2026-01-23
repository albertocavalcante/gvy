package com.github.albertocavalcante.reports.results.parsers

import com.github.albertocavalcante.reports.results.model.TestResultStatus
import com.github.albertocavalcante.reports.results.model.TestResultSummary
import com.github.albertocavalcante.reports.results.model.TestResultsResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SurefireParserTest {

    @Test
    fun `should parse valid Surefire XML report`(@TempDir tempDir: Path) {
        val testXmlContent = javaClass.classLoader.getResource("surefire-reports/TEST-com.example.MySpec.xml")
            ?.readText() ?: error("Test resource not found")
        val surefireDir = File(tempDir.toFile(), "target/surefire-reports")
        surefireDir.mkdirs()
        val xmlFile = File(surefireDir, "TEST-com.example.MySpec.xml")
        xmlFile.writeText(testXmlContent)

        val results = SurefireParser.parseReportFile(xmlFile)

        assertEquals(4, results.size)

        // Test success case
        val successTest = results.find { it.name == "testSuccess" }
        assertNotNull(successTest)
        assertEquals(TestResultStatus.SUCCESS, successTest!!.status)
        assertEquals("com.example.MySpec.testSuccess", successTest.testId)
        assertEquals("com.example.MySpec", successTest.className)
        assertEquals(123L, successTest.durationMs)
        assertEquals("Test output here", successTest.output)
        assertNull(successTest.failureMessage)

        // Test failure case
        val failureTest = results.find { it.name == "testFailure" }
        assertNotNull(failureTest)
        assertEquals(TestResultStatus.FAILURE, failureTest!!.status)
        assertEquals("Expected true but was false", failureTest.failureMessage)
        assertNotNull(failureTest.stackTrace)
        assertTrue(failureTest.stackTrace!!.contains("org.opentest4j.AssertionFailedError"))
        assertEquals(456L, failureTest.durationMs)

        // Test skipped case
        val skippedTest = results.find { it.name == "testSkipped" }
        assertNotNull(skippedTest)
        assertEquals(TestResultStatus.SKIPPED, skippedTest!!.status)
        assertEquals("Test disabled", skippedTest.failureMessage)
        assertEquals(0L, skippedTest.durationMs)

        // Test error case
        val errorTest = results.find { it.name == "testError" }
        assertNotNull(errorTest)
        assertEquals(TestResultStatus.ERROR, errorTest!!.status)
        assertEquals("NullPointerException", errorTest.failureMessage)
        assertNotNull(errorTest.stackTrace)
        assertTrue(errorTest.stackTrace!!.contains("java.lang.NullPointerException"))
        assertEquals(234L, errorTest.durationMs)
    }

    @Test
    fun `should handle empty Surefire reports directory`(@TempDir tempDir: Path) {
        val response = SurefireParser.parseWorkspace(tempDir.toFile())

        assertEquals(0, response.results.size)
        assertEquals(0, response.summary.total)
        assertEquals(0, response.summary.passed)
        assertEquals(0, response.summary.failed)
        assertEquals(0, response.summary.skipped)
        assertEquals(0, response.summary.errors)
    }

    @Test
    fun `should compute correct summary`(@TempDir tempDir: Path) {
        val testXmlContent = javaClass.classLoader.getResource("surefire-reports/TEST-com.example.MySpec.xml")
            ?.readText() ?: error("Test resource not found")
        val surefireDir = File(tempDir.toFile(), "target/surefire-reports")
        surefireDir.mkdirs()
        val xmlFile = File(surefireDir, "TEST-com.example.MySpec.xml")
        xmlFile.writeText(testXmlContent)

        val results = SurefireParser.parseReportFile(xmlFile)
        val response = TestResultsResponse(
            results = results,
            summary = TestResultSummary(
                total = results.size,
                passed = results.count { it.status == TestResultStatus.SUCCESS },
                failed = results.count { it.status == TestResultStatus.FAILURE },
                skipped = results.count { it.status == TestResultStatus.SKIPPED },
                errors = results.count { it.status == TestResultStatus.ERROR },
                durationMs = results.sumOf { it.durationMs },
            ),
        )

        assertEquals(4, response.summary.total)
        assertEquals(1, response.summary.passed)
        assertEquals(1, response.summary.failed)
        assertEquals(1, response.summary.skipped)
        assertEquals(1, response.summary.errors)
        assertEquals(813L, response.summary.durationMs) // 123 + 456 + 0 + 234
    }

    @Test
    fun `should discover surefire and failsafe reports`(@TempDir tempDir: Path) {
        // Create directory structure
        val surefireDir = File(tempDir.toFile(), "target/surefire-reports")
        val failsafeDir = File(tempDir.toFile(), "target/failsafe-reports")
        surefireDir.mkdirs()
        failsafeDir.mkdirs()

        // Copy test resource to both directories
        val testXmlContent = javaClass.classLoader.getResource("surefire-reports/TEST-com.example.MySpec.xml")
            ?.readText() ?: error("Test resource not found")

        File(surefireDir, "TEST-com.example.MySpec.xml").writeText(testXmlContent)
        File(failsafeDir, "TEST-com.example.IntegrationTest.xml").writeText(testXmlContent)

        val response = SurefireParser.parseWorkspace(tempDir.toFile())

        // Should find reports from both directories
        assertEquals(8, response.results.size) // 4 tests * 2 files
    }

    @Test
    fun `should handle multi-module Maven project`(@TempDir tempDir: Path) {
        // Create multi-module structure
        val moduleDir = File(tempDir.toFile(), "module1")
        val surefireDir = File(moduleDir, "target/surefire-reports")
        surefireDir.mkdirs()

        // Create pom.xml with module declaration
        File(tempDir.toFile(), "pom.xml").writeText(
            """
            <?xml version="1.0"?>
            <project>
                <modules>
                    <module>module1</module>
                </modules>
            </project>
            """.trimIndent(),
        )

        // Copy test resource to module
        val testXmlContent = javaClass.classLoader.getResource("surefire-reports/TEST-com.example.MySpec.xml")
            ?.readText() ?: error("Test resource not found")
        File(surefireDir, "TEST-com.example.MySpec.xml").writeText(testXmlContent)

        val response = SurefireParser.parseWorkspace(tempDir.toFile())

        assertEquals(4, response.results.size)
    }

    @Test
    fun `should handle malformed XML gracefully`(@TempDir tempDir: Path) {
        val surefireDir = File(tempDir.toFile(), "target/surefire-reports")
        surefireDir.mkdirs()

        // Write malformed XML
        File(surefireDir, "TEST-Malformed.xml").writeText("<invalid>xml")

        // Should not throw, just log warning and continue
        val response = SurefireParser.parseWorkspace(tempDir.toFile())

        assertEquals(0, response.results.size)
    }

    @Test
    fun `should parse time attribute correctly`(@TempDir tempDir: Path) {
        val testXmlContent = javaClass.classLoader.getResource("surefire-reports/TEST-com.example.MySpec.xml")
            ?.readText() ?: error("Test resource not found")
        val surefireDir = File(tempDir.toFile(), "target/surefire-reports")
        surefireDir.mkdirs()
        val xmlFile = File(surefireDir, "TEST-com.example.MySpec.xml")
        xmlFile.writeText(testXmlContent)

        val results = SurefireParser.parseReportFile(xmlFile)

        // Time in XML is in seconds (0.123), should be converted to milliseconds (123)
        val successTest = results.find { it.name == "testSuccess" }
        assertEquals(123L, successTest?.durationMs)
    }

    @Test
    fun `should handle test without classname`(@TempDir tempDir: Path) {
        val surefireDir = File(tempDir.toFile(), "target/surefire-reports")
        surefireDir.mkdirs()

        val xmlContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="TestSuite" time="1.0" tests="1" errors="0" skipped="0" failures="0">
              <testcase name="testWithoutClass" time="0.5"/>
            </testsuite>
        """.trimIndent()

        File(surefireDir, "TEST-NoClass.xml").writeText(xmlContent)

        val response = SurefireParser.parseWorkspace(tempDir.toFile())

        assertEquals(1, response.results.size)
        val test = response.results[0]
        assertEquals("testWithoutClass", test.testId)
        assertNull(test.className)
    }

    @Test
    fun `should filter only TEST- XML files`(@TempDir tempDir: Path) {
        val surefireDir = File(tempDir.toFile(), "target/surefire-reports")
        surefireDir.mkdirs()

        // Create valid test file
        val testXmlContent = javaClass.classLoader.getResource("surefire-reports/TEST-com.example.MySpec.xml")
            ?.readText() ?: error("Test resource not found")
        File(surefireDir, "TEST-Valid.xml").writeText(testXmlContent)

        // Create files that should be ignored
        File(surefireDir, "other.xml").writeText("<test/>")
        File(surefireDir, "TEST-Invalid.txt").writeText("not xml")
        File(surefireDir, "README.md").writeText("# Readme")

        val response = SurefireParser.parseWorkspace(tempDir.toFile())

        // Should only parse TEST-*.xml files
        assertEquals(4, response.results.size)
    }
}

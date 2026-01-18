package com.github.albertocavalcante.reports.coverage.parsers

import com.github.albertocavalcante.reports.coverage.model.CoverageResponse
import com.github.albertocavalcante.reports.coverage.model.CoverageSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class JacocoParserTest {

    @Test
    fun `should parse valid JaCoCo XML report`(@TempDir tempDir: Path) {
        val testXmlContent = javaClass.classLoader.getResource("jacoco/jacoco.xml")
            ?.readText() ?: error("Test resource not found")
        val jacocoDir = File(tempDir.toFile(), "target/site/jacoco")
        jacocoDir.mkdirs()
        val xmlFile = File(jacocoDir, "jacoco.xml")
        xmlFile.writeText(testXmlContent)
        val workspaceRoot = tempDir.toFile()

        val results = JacocoParser.parseReportFile(xmlFile, workspaceRoot)

        assertEquals(3, results.size)

        // Test MyClass.groovy
        val myClass = results.find { it.uri.contains("MyClass.groovy") }
        assertNotNull(myClass)
        assertEquals(4, myClass!!.lines.size)

        // Line 10: covered (ci=3)
        val line10 = myClass.lines.find { it.line == 10 }
        assertNotNull(line10)
        assertTrue(line10!!.covered)
        assertEquals(3, line10.hitCount)
        assertEquals(null, line10.branchInfo)

        // Line 11: not covered (mi=5, ci=0)
        val line11 = myClass.lines.find { it.line == 11 }
        assertNotNull(line11)
        assertFalse(line11!!.covered)
        assertEquals(0, line11.hitCount)

        // Line 12: covered with branches (ci=2, mb=2, cb=1)
        val line12 = myClass.lines.find { it.line == 12 }
        assertNotNull(line12)
        assertTrue(line12!!.covered)
        assertEquals(2, line12.hitCount)
        assertNotNull(line12.branchInfo)
        assertEquals(1, line12.branchInfo!!.covered)
        assertEquals(3, line12.branchInfo!!.total) // mb + cb = 2 + 1

        // Line 13: covered with branches (ci=1, mb=0, cb=2)
        val line13 = myClass.lines.find { it.line == 13 }
        assertNotNull(line13)
        assertTrue(line13!!.covered)
        assertEquals(1, line13.hitCount)
        assertNotNull(line13.branchInfo)
        assertEquals(2, line13.branchInfo!!.covered)
        assertEquals(2, line13.branchInfo!!.total)

        // Check summary for MyClass
        assertEquals(4, myClass.summary.linesTotal)
        assertEquals(3, myClass.summary.linesCovered)
        assertEquals(5, myClass.summary.branchesTotal)
        assertEquals(3, myClass.summary.branchesCovered)
    }

    @Test
    fun `should compute overall coverage summary correctly`(@TempDir tempDir: Path) {
        val testXmlContent = javaClass.classLoader.getResource("jacoco/jacoco.xml")
            ?.readText() ?: error("Test resource not found")
        val jacocoDir = File(tempDir.toFile(), "target/site/jacoco")
        jacocoDir.mkdirs()
        val xmlFile = File(jacocoDir, "jacoco.xml")
        xmlFile.writeText(testXmlContent)
        val workspaceRoot = tempDir.toFile()

        val response = JacocoParser.parseReportFile(xmlFile, workspaceRoot)
        val coverage = CoverageResponse(
            files = response,
            summary = CoverageSummary(
                lineCoveragePercent = 0.0,
                branchCoveragePercent = 0.0,
                linesCovered = response.sumOf { it.summary.linesCovered },
                linesTotal = response.sumOf { it.summary.linesTotal },
                branchesCovered = response.sumOf { it.summary.branchesCovered },
                branchesTotal = response.sumOf { it.summary.branchesTotal },
            ),
        )

        // MyClass: 4 lines (3 covered), AnotherClass: 2 lines (2 covered), SubClass: 1 line (1 covered)
        assertEquals(7, coverage.summary.linesTotal)
        assertEquals(6, coverage.summary.linesCovered)

        // MyClass: 5 branches (3 covered), others: 0 branches
        assertEquals(5, coverage.summary.branchesTotal)
        assertEquals(3, coverage.summary.branchesCovered)
    }

    @Test
    fun `should handle empty JaCoCo report`(@TempDir tempDir: Path) {
        val response = JacocoParser.parseWorkspace(tempDir.toFile())

        assertEquals(0, response.files.size)
        assertEquals(0.0, response.summary.lineCoveragePercent)
        assertEquals(0.0, response.summary.branchCoveragePercent)
        assertEquals(0, response.summary.linesCovered)
        assertEquals(0, response.summary.linesTotal)
    }

    @Test
    fun `should discover JaCoCo reports in standard locations`(@TempDir tempDir: Path) {
        // Create Gradle location
        val gradleDir = File(tempDir.toFile(), "build/reports/jacoco/test")
        gradleDir.mkdirs()

        // Create Maven location
        val mavenDir = File(tempDir.toFile(), "target/site/jacoco")
        mavenDir.mkdirs()

        val testXmlContent = javaClass.classLoader.getResource("jacoco/jacoco.xml")
            ?.readText() ?: error("Test resource not found")

        File(gradleDir, "jacocoTestReport.xml").writeText(testXmlContent)
        File(mavenDir, "jacoco.xml").writeText(testXmlContent)

        val response = JacocoParser.parseWorkspace(tempDir.toFile())

        // Should find and merge coverage from both locations
        assertEquals(3, response.files.size)
    }

    @Test
    fun `should handle multi-module Maven project`(@TempDir tempDir: Path) {
        // Create multi-module structure
        val moduleDir = File(tempDir.toFile(), "module1")
        val jacocoDir = File(moduleDir, "target/site/jacoco")
        jacocoDir.mkdirs()

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

        val testXmlContent = javaClass.classLoader.getResource("jacoco/jacoco.xml")
            ?.readText() ?: error("Test resource not found")
        File(jacocoDir, "jacoco.xml").writeText(testXmlContent)

        val response = JacocoParser.parseWorkspace(tempDir.toFile())

        assertEquals(3, response.files.size)
    }

    @Test
    fun `should handle Gradle subprojects`(@TempDir tempDir: Path) {
        // Create Gradle subproject structure
        val subprojectDir = File(tempDir.toFile(), "subproject1")
        subprojectDir.mkdirs()

        // Create build.gradle to indicate it's a Gradle project
        File(subprojectDir, "build.gradle").writeText("// Gradle build file")

        val jacocoDir = File(subprojectDir, "build/reports/jacoco/test")
        jacocoDir.mkdirs()

        val testXmlContent = javaClass.classLoader.getResource("jacoco/jacoco.xml")
            ?.readText() ?: error("Test resource not found")
        File(jacocoDir, "jacocoTestReport.xml").writeText(testXmlContent)

        val response = JacocoParser.parseWorkspace(tempDir.toFile())

        assertEquals(3, response.files.size)
    }

    @Test
    fun `should handle malformed JaCoCo XML gracefully`(@TempDir tempDir: Path) {
        val jacocoDir = File(tempDir.toFile(), "target/site/jacoco")
        jacocoDir.mkdirs()

        // Write malformed XML
        File(jacocoDir, "jacoco.xml").writeText("<invalid>xml")

        // Should not throw, just log warning and continue
        val response = JacocoParser.parseWorkspace(tempDir.toFile())

        assertEquals(0, response.files.size)
    }

    @Test
    fun `should merge coverage from multiple reports for same file`(@TempDir tempDir: Path) {
        val dir1 = File(tempDir.toFile(), "module1/target/site/jacoco")
        val dir2 = File(tempDir.toFile(), "module2/target/site/jacoco")
        dir1.mkdirs()
        dir2.mkdirs()

        // Create pom.xml
        File(tempDir.toFile(), "pom.xml").writeText(
            """
            <?xml version="1.0"?>
            <project>
                <modules>
                    <module>module1</module>
                    <module>module2</module>
                </modules>
            </project>
            """.trimIndent(),
        )

        // Create source file to ensure URIs match
        val srcDir = File(tempDir.toFile(), "src/main/groovy/com/example")
        srcDir.mkdirs()
        File(srcDir, "MyClass.groovy").writeText("// source file")

        // First report: line 10 covered
        val xml1 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <report name="module1">
              <package name="com/example">
                <sourcefile name="MyClass.groovy">
                  <line nr="10" mi="0" ci="3" mb="0" cb="0"/>
                </sourcefile>
              </package>
            </report>
        """.trimIndent()

        // Second report: line 10 covered with higher hit count, line 11 covered
        val xml2 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <report name="module2">
              <package name="com/example">
                <sourcefile name="MyClass.groovy">
                  <line nr="10" mi="0" ci="5" mb="0" cb="0"/>
                  <line nr="11" mi="0" ci="2" mb="0" cb="0"/>
                </sourcefile>
              </package>
            </report>
        """.trimIndent()

        File(dir1, "jacoco.xml").writeText(xml1)
        File(dir2, "jacoco.xml").writeText(xml2)

        val response = JacocoParser.parseWorkspace(tempDir.toFile())

        // Should have one file with merged coverage
        assertEquals(1, response.files.size)
        val myClass = response.files[0]
        assertEquals(2, myClass.lines.size)

        // Line 10 should have max hit count (5)
        val line10 = myClass.lines.find { it.line == 10 }
        assertEquals(5, line10?.hitCount)

        // Line 11 should be present
        val line11 = myClass.lines.find { it.line == 11 }
        assertNotNull(line11)
        assertTrue(line11!!.covered)
    }

    @Test
    fun `should calculate percentage correctly`(@TempDir tempDir: Path) {
        val jacocoDir = File(tempDir.toFile(), "target/site/jacoco")
        jacocoDir.mkdirs()

        val testXmlContent = javaClass.classLoader.getResource("jacoco/jacoco.xml")
            ?.readText() ?: error("Test resource not found")
        File(jacocoDir, "jacoco.xml").writeText(testXmlContent)

        val response = JacocoParser.parseWorkspace(tempDir.toFile())

        // 6 covered out of 7 total lines = 85.71%
        assertEquals(85.71, response.summary.lineCoveragePercent, 0.01)

        // 3 covered out of 5 total branches = 60%
        assertEquals(60.0, response.summary.branchCoveragePercent, 0.01)
    }

    @Test
    fun `should handle files with no coverage lines`(@TempDir tempDir: Path) {
        val jacocoDir = File(tempDir.toFile(), "target/site/jacoco")
        jacocoDir.mkdirs()

        val xmlContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <report name="test">
              <package name="com/example">
                <sourcefile name="EmptyClass.groovy">
                </sourcefile>
              </package>
            </report>
        """.trimIndent()

        File(jacocoDir, "jacoco.xml").writeText(xmlContent)

        val response = JacocoParser.parseWorkspace(tempDir.toFile())

        // Should skip files with no lines
        assertEquals(0, response.files.size)
    }

    @Test
    fun `should create valid URI for files in common source directories`(@TempDir tempDir: Path) {
        val jacocoDir = File(tempDir.toFile(), "target/site/jacoco")
        jacocoDir.mkdirs()

        // Create actual source file
        val srcDir = File(tempDir.toFile(), "src/main/groovy/com/example")
        srcDir.mkdirs()
        File(srcDir, "MyClass.groovy").writeText("// source")

        val xmlContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <report name="test">
              <package name="com/example">
                <sourcefile name="MyClass.groovy">
                  <line nr="1" mi="0" ci="1" mb="0" cb="0"/>
                </sourcefile>
              </package>
            </report>
        """.trimIndent()

        File(jacocoDir, "jacoco.xml").writeText(xmlContent)

        val response = JacocoParser.parseWorkspace(tempDir.toFile())

        assertEquals(1, response.files.size)
        val file = response.files[0]
        assertTrue(file.uri.startsWith("file:"))
        assertTrue(file.uri.contains("MyClass.groovy"))
    }
}

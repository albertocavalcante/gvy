package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovyjenkins.completion.JenkinsContextDetector
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for completion context detection integration.
 *
 * These tests verify that JenkinsContextDetector correctly identifies
 * various Jenkinsfile block contexts used by CompletionProvider.
 *
 * HEURISTICS DOCUMENTATION:
 * 1. Text-based detection (JenkinsContextDetector) is used instead of AST-based
 *    detection because it's more robust during editing when the AST may be incomplete.
 * 2. Block context is determined by analyzing opening braces and block keywords.
 * 3. Brace depth tracking handles nested blocks correctly.
 *
 * FUTURE IMPROVEMENTS:
 * - Consider hybrid approach: use AST when available, fallback to text for broken syntax
 * - Add incremental parsing support for better performance
 */
class CompletionContextIntegrationTest {

    // =====================================================================
    // Options Block Detection Tests
    // =====================================================================

    @Test
    fun `options block should be detected at start of empty line`() {
        val lines = listOf(
            "pipeline {",
            "    options {",
            "        ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 2, column = 8)

        assertTrue(context.isOptionsContext, "Should detect options block context")
        assertFalse(context.isStepsContext, "Should not detect steps context in options")
    }

    @Test
    fun `options block should be detected with partial content`() {
        val lines = listOf(
            "pipeline {",
            "    options {",
            "        disableCo",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 2, column = 17)

        assertTrue(context.isOptionsContext, "Should detect options block context with partial text")
    }

    @Test
    fun `nested options inside stage should still be detected`() {
        // Note: This is technically invalid Jenkins syntax, but we should handle it gracefully
        val lines = listOf(
            "pipeline {",
            "    stages {",
            "        stage('Test') {",
            "            options {",
            "                ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 4, column = 16)

        assertTrue(context.isOptionsContext, "Should detect nested options block")
        assertTrue(context.isStageContext, "Should also be in stage context")
    }

    // =====================================================================
    // Steps Block Detection Tests
    // =====================================================================

    @Test
    fun `steps block should be detected correctly`() {
        val lines = listOf(
            "pipeline {",
            "    stages {",
            "        stage('Build') {",
            "            steps {",
            "                ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 4, column = 16)

        assertTrue(context.isStepsContext, "Should detect steps block context")
        assertFalse(context.isOptionsContext, "Should NOT be in options context")
    }

    @Test
    fun `steps inside post block should be detected correctly`() {
        val lines = listOf(
            "pipeline {",
            "    post {",
            "        always {",
            "            ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 3, column = 12)

        assertTrue(context.isPostContext, "Should detect post block context")
        assertEquals("always", context.postCondition, "Should identify post condition")
    }

    @Test
    fun `notBuilt and unsuccessful post conditions should be detected`() {
        val lines = listOf(
            "pipeline {",
            "    post {",
            "        notBuilt {",
            "            ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 3, column = 12)
        assertTrue(context.isPostContext)
        assertEquals("notBuilt", context.postCondition)

        val lines2 = listOf(
            "pipeline {",
            "    post {",
            "        unsuccessful {",
            "            ",
        )
        val context2 = JenkinsContextDetector.detectFromDocument(lines2, lineNumber = 3, column = 12)
        assertTrue(context2.isPostContext)
        assertEquals("unsuccessful", context2.postCondition)
    }

    // =====================================================================
    // Edge Cases and Determinism Tests
    // =====================================================================

    @Test
    fun `empty document should return top-level context`() {
        val lines = emptyList<String>()
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 0, column = 0)

        assertTrue(context.isTopLevel, "Empty document should be top-level")
        assertFalse(context.isOptionsContext, "Empty document should not have options context")
        assertFalse(context.isStepsContext, "Empty document should not have steps context")
    }

    @Test
    fun `single line document should handle correctly`() {
        val lines = listOf("pipeline {")
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 0, column = 10)

        assertTrue(context.isDeclarativePipeline, "Should detect declarative pipeline start")
    }

    @Test
    fun `line number out of bounds should not crash`() {
        val lines = listOf("pipeline {", "}")
        // Request beyond document length
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 10, column = 0)

        // Should handle gracefully - exact behavior may vary
        assertFalse(context.isOptionsContext)
    }

    @Test
    fun `column beyond line length should handle gracefully`() {
        val lines = listOf("pipeline {", "    options {", "    }")
        // Column way beyond line content
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 1, column = 100)

        assertTrue(context.isOptionsContext, "Large column should still detect block context")
    }

    // =====================================================================
    // Block Transition Tests
    // =====================================================================

    @Test
    fun `closing brace should exit block context`() {
        val lines = listOf(
            "pipeline {",
            "    options {",
            "    }",
            "    stages {",
            "        ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 4, column = 8)

        assertFalse(context.isOptionsContext, "Should have exited options block")
        // Note: stages block doesn't set a specific context flag
        assertEquals(listOf("pipeline", "stages"), context.enclosingBlocks)
    }

    @Test
    fun `multiple nested blocks should track correctly`() {
        val lines = listOf(
            "pipeline {",
            "    stages {",
            "        stage('Test') {",
            "            steps {",
            "                script {",
            "                    ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 5, column = 20)

        assertEquals(
            listOf("pipeline", "stages", "stage", "steps", "script"),
            context.enclosingBlocks,
            "Should track all enclosing blocks",
        )
        assertTrue(context.isScriptContext, "Should be in script context")
        assertTrue(context.isStepsContext, "Should also be in steps context")
    }

    // =====================================================================
    // Scripted vs Declarative Pipeline Tests
    // =====================================================================

    @Test
    fun `scripted pipeline should be detected`() {
        val lines = listOf(
            "node {",
            "    stage('Build') {",
            "        ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 2, column = 8)

        assertTrue(context.isScriptedPipeline, "Should detect scripted pipeline")
        assertFalse(context.isDeclarativePipeline, "Should not be declarative")
        assertTrue(context.isInNode, "Should be in node block")
    }

    @Test
    fun `declarative pipeline should be detected`() {
        val lines = listOf(
            "pipeline {",
            "    agent any",
            "    ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 2, column = 4)

        assertTrue(context.isDeclarativePipeline, "Should detect declarative pipeline")
        assertFalse(context.isScriptedPipeline, "Should not be scripted")
    }

    // =====================================================================
    // Comment and Inline Block Tests
    // =====================================================================

    @Test
    fun `blocks in comments should be ignored`() {
        val lines = listOf(
            "pipeline {",
            "    // options {",
            "    stages {",
            "        ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 3, column = 8)

        assertFalse(context.isOptionsContext, "Should ignore options block in comment")
        assertEquals(listOf("pipeline", "stages"), context.enclosingBlocks)
    }

    @Test
    fun `blocks after inline comments should be ignored`() {
        val lines = listOf(
            "pipeline { // stages {",
            "    ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 1, column = 4)

        assertEquals(listOf("pipeline"), context.enclosingBlocks, "Should ignore blocks after //")
    }

    @Test
    fun `multiple blocks on same line should be detected`() {
        val lines = listOf(
            "pipeline { stages { stage('Test') { steps {",
            "    ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 1, column = 4)

        assertEquals(
            listOf("pipeline", "stages", "stage", "steps"),
            context.enclosingBlocks,
            "Should detect all blocks on the same line",
        )
    }
}

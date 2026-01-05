package com.github.albertocavalcante.groovyjenkins.completion

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for JenkinsContextDetector - detects cursor context in Jenkinsfiles.
 *
 * TDD RED phase: These tests define expected context detection behavior.
 */
class JenkinsContextDetectorTest {

    @Test
    fun `detects env dot context`() {
        val line = "    def x = env."
        val context = JenkinsContextDetector.detectFromLine(line, position = 16)

        assertTrue(context.isEnvContext)
        assertFalse(context.isPostContext)
        assertFalse(context.isOptionsContext)
    }

    @Test
    fun `detects env dot context with partial text`() {
        val line = "    def x = env.BU"
        val context = JenkinsContextDetector.detectFromLine(line, position = 18)

        assertTrue(context.isEnvContext)
        assertEquals("BU", context.partialText)
    }

    @Test
    fun `detects params dot context`() {
        val line = "    if (params."
        val context = JenkinsContextDetector.detectFromLine(line, position = 15)

        assertTrue(context.isParamsContext)
    }

    @Test
    fun `detects currentBuild dot context`() {
        val line = "    echo currentBuild."
        val context = JenkinsContextDetector.detectFromLine(line, position = 22)

        assertTrue(context.isCurrentBuildContext)
    }

    @Test
    fun `detects post block context`() {
        val lines = listOf(
            "pipeline {",
            "    stages { }",
            "    post {",
            "        ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 3, column = 8)

        assertTrue(context.isPostContext)
        assertEquals("post", context.currentBlock)
    }

    @Test
    fun `detects options block context`() {
        val lines = listOf(
            "pipeline {",
            "    options {",
            "        ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 2, column = 8)

        assertTrue(context.isOptionsContext)
    }

    @Test
    fun `detects agent block context`() {
        val lines = listOf(
            "pipeline {",
            "    agent {",
            "        ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 2, column = 8)

        assertTrue(context.isAgentContext)
        assertEquals("agent", context.currentBlock)
    }

    @Test
    fun `detects stage block context`() {
        val lines = listOf(
            "pipeline {",
            "    stages {",
            "        stage('Build') {",
            "            ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 3, column = 12)

        assertTrue(context.isStageContext)
    }

    @Test
    fun `detects steps block context`() {
        val lines = listOf(
            "pipeline {",
            "    stages {",
            "        stage('Build') {",
            "            steps {",
            "                ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 4, column = 16)

        assertTrue(context.isStepsContext)
        assertEquals("steps", context.currentBlock)
    }

    @Test
    fun `detects when block context`() {
        val lines = listOf(
            "pipeline {",
            "    stages {",
            "        stage('Deploy') {",
            "            when {",
            "                ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 4, column = 16)

        assertTrue(context.isWhenContext)
    }

    @Test
    fun `detects environment block context`() {
        val lines = listOf(
            "pipeline {",
            "    environment {",
            "        ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 2, column = 8)

        assertTrue(context.isEnvironmentContext)
    }

    @Test
    fun `detects parameters block context`() {
        val lines = listOf(
            "pipeline {",
            "    parameters {",
            "        ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 2, column = 8)

        assertTrue(context.isParametersContext)
    }

    @Test
    fun `detects triggers block context`() {
        val lines = listOf(
            "pipeline {",
            "    triggers {",
            "        ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 2, column = 8)

        assertTrue(context.isTriggersContext)
    }

    @Test
    fun `detects tools block context`() {
        val lines = listOf(
            "pipeline {",
            "    tools {",
            "        ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 2, column = 8)

        assertTrue(context.isToolsContext)
    }

    @Test
    fun `detects script block context in declarative`() {
        val lines = listOf(
            "pipeline {",
            "    stages {",
            "        stage('Build') {",
            "            steps {",
            "                script {",
            "                    ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 5, column = 20)

        assertTrue(context.isScriptContext)
    }

    @Test
    fun `detects nested post success context`() {
        val lines = listOf(
            "pipeline {",
            "    post {",
            "        success {",
            "            ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 3, column = 12)

        assertTrue(context.isPostContext)
        assertEquals("success", context.postCondition)
    }

    @Test
    fun `current block reports success inside post condition`() {
        val lines = listOf(
            "pipeline {",
            "    post {",
            "        success {",
            "            ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 4, column = 12)

        assertEquals("success", context.currentBlock)
    }

    @Test
    fun `detects step parameter context`() {
        val lines = listOf(
            "pipeline {",
            "    stages {",
            "        stage('Test') {",
            "            steps {",
            "                sh ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 4, column = 19)

        assertTrue(context.isStepParameterContext)
        assertEquals("sh", context.currentStepName)
    }

    @Test
    fun `detects step named parameter context`() {
        val line = "                sh script: 'test', return"
        val context = JenkinsContextDetector.detectFromLine(line, position = 40)

        assertTrue(context.isStepParameterContext)
        assertEquals("sh", context.currentStepName)
    }

    @Test
    fun `handles empty document`() {
        val lines = emptyList<String>()
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 0, column = 0)

        assertFalse(context.isPostContext)
        assertFalse(context.isOptionsContext)
        assertTrue(context.isTopLevel)
    }

    @Test
    fun `handles scripted pipeline context`() {
        val lines = listOf(
            "node {",
            "    stage('Build') {",
            "        ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 2, column = 8)

        assertTrue(context.isScriptedPipeline)
        assertTrue(context.isInNode)
    }

    @Test
    fun `identifies declarative pipeline`() {
        val lines = listOf(
            "pipeline {",
            "    agent any",
            "    stages {",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 2, column = 4)

        assertTrue(context.isDeclarativePipeline)
        assertFalse(context.isScriptedPipeline)
    }

    @Test
    fun `tracks enclosing blocks`() {
        val lines = listOf(
            "pipeline {",
            "    stages {",
            "        stage('Build') {",
            "            steps {",
            "                ",
        )
        val context = JenkinsContextDetector.detectFromDocument(lines, lineNumber = 4, column = 16)

        assertEquals(listOf("pipeline", "stages", "stage", "steps"), context.enclosingBlocks)
    }
}

// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.completion

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JenkinsSourceContextTest {
    @Test
    fun `scripted code remains executable outside node and inside arbitrary closures`() {
        val examples = listOf(
            "|echo 'hello'",
            "def helper() { |echo 'hello' }",
            "timeout(time: 2) { |echo 'hello' }",
            "for (item in items) { |echo item }",
            "parallel(first: { |echo 'hello' })",
            "def branches = [first: { |echo 'hello' }]",
        )
        examples.forEach { assertRegion(JenkinsRegion.EXECUTABLE_GROOVY, it) }
    }

    @Test
    fun `declarative structure and executable bodies are distinct`() {
        val structural = listOf(
            "pipeline { | }",
            "pipeline { options { | } }",
            "pipeline { agent { node { | } } }",
            "pipeline { parameters { | } }",
            "pipeline { stages { stage('Build') { | } } }",
            "pipeline { post { | } }",
        )
        val executable = listOf(
            "pipeline { stages { stage('Build') { steps { | } } } }",
            "pipeline { stages { stage('Build') { steps { script { | } } } } }",
            "pipeline { stages { stage('Build') { when { expression { | } } } } }",
            "pipeline { post { always { | } } }",
            "pipeline { stages { stage('Build') { steps { timeout(time: 2) { | } } } } }",
        )
        structural.forEach { assertRegion(JenkinsRegion.DECLARATIVE_STRUCTURE, it) }
        executable.forEach { assertRegion(JenkinsRegion.EXECUTABLE_GROOVY, it) }
    }

    @Test
    fun `declarative pipeline in a shared-library call method has structural context`() {
        assertRegion(
            JenkinsRegion.DECLARATIVE_STRUCTURE,
            "def call() { pipeline { options { |timeout(time: 1) } } }",
        )
    }

    @Test
    fun `unknown braces in structure are uncertain and cannot regain structural status`() {
        assertRegion(JenkinsRegion.UNCERTAIN, "pipeline { options { custom { | } } }")
        assertRegion(JenkinsRegion.UNCERTAIN, "pipeline { options { custom { stage('x') { | } } } }")
        assertRegion(JenkinsRegion.EXECUTABLE_GROOVY, "pipeline { stages { stage('x') { steps { custom { | } } } } }")
    }

    @Test
    fun `comments strings unicode and cursor suffix do not change the source context`() {
        assertRegion(
            JenkinsRegion.EXECUTABLE_GROOVY,
            "pipeline { stages { stage('🚀') { steps { echo 'options { }'; /* pipeline { */ | } } } }",
        )
        assertRegion(JenkinsRegion.DECLARATIVE_STRUCTURE, "pipeline { options { timeout(time: |) } }")
    }

    @Test
    fun `declarative step bodies resolve steps without allowing arbitrary Groovy statements`() {
        val cases = mapOf(
            "|echo 'hello'" to true,
            "def helper() { | }" to true,
            "pipeline { stages { stage('x') { steps { | } } } }" to false,
            "pipeline { stages { stage('x') { steps { timeout(time: 2) { | } } } } }" to false,
            "pipeline { post { always { | } } }" to false,
            "pipeline { stages { stage('x') { steps { script { | } } } } }" to true,
            "pipeline { stages { stage('x') { when { expression { | } } } } }" to true,
        )
        cases.forEach { (source, allowed) ->
            assertEquals(allowed, contextAtMarker(source).allowsGroovyStatements, source)
        }
    }

    @Test
    fun `multiline declarative openers preserve structure and transition into steps`() {
        val prefix = "pipeline\r\n{\r\n stages {\r\n  stage(\r\n    '🚀'\r\n  )\r\n  {\r\n"
        assertRegion(JenkinsRegion.DECLARATIVE_STRUCTURE, prefix + "   |\r\n  }\r\n }\r\n}")
        assertRegion(JenkinsRegion.EXECUTABLE_GROOVY, prefix + "   steps\r\n   { | }\r\n  }\r\n }\r\n}")
    }

    @Test
    fun `bare carriage returns delimit LSP lines in declarative structure`() {
        val snapshot = JenkinsSourceContext("pipeline {\roptions {\r  timeout(time: 1)\r}\r}")

        assertEquals(JenkinsRegion.DECLARATIVE_STRUCTURE, snapshot.at(2, 2).region)
        assertEquals("options", snapshot.at(2, 2).currentBlock)
    }

    @Test
    fun `invalid positions are uncertain and a snapshot can answer multiple positions`() {
        val snapshot = JenkinsSourceContext("pipeline { options { } }\necho 'done'")
        assertEquals(JenkinsRegion.UNCERTAIN, snapshot.at(-1, 0).region)
        assertEquals(JenkinsRegion.UNCERTAIN, snapshot.at(0, 100).region)
        assertEquals(JenkinsRegion.DECLARATIVE_STRUCTURE, snapshot.at(0, 20).region)
        assertEquals(JenkinsRegion.EXECUTABLE_GROOVY, snapshot.at(1, 0).region)
        assertEquals(JenkinsRegion.DECLARATIVE_STRUCTURE, snapshot.at(0, 20).region)
    }

    @Test
    fun `deep incomplete source remains queryable without copying every ancestor stack`() {
        val depth = 20_000
        val source = "pipeline { steps { " + "wrapper { ".repeat(depth)
        val snapshot = JenkinsSourceContext(source)

        assertEquals(JenkinsRegion.EXECUTABLE_GROOVY, snapshot.at(0, source.length).region)
        assertEquals(JenkinsRegion.DECLARATIVE_STRUCTURE, snapshot.at(0, "pipeline { ".length).region)
    }

    @Test
    fun `legacy context recognizes top level scripted code`() {
        val context = JenkinsContextDetector.detectFromDocument(listOf("echo 'hello'"), 0, 4)
        assertTrue(context.isScriptedPipeline)
    }

    private fun assertRegion(expected: JenkinsRegion, markedSource: String) {
        assertEquals(expected, contextAtMarker(markedSource).region, markedSource)
    }

    private fun contextAtMarker(markedSource: String): JenkinsPositionContext {
        val marker = markedSource.indexOf('|')
        require(marker >= 0)
        val source = markedSource.removeRange(marker, marker + 1)
        val before = source.substring(0, marker)
        val line = before.count { it == '\n' }
        val character = before.substringAfterLast('\n').length
        return JenkinsSourceContext(source).at(line, character)
    }
}

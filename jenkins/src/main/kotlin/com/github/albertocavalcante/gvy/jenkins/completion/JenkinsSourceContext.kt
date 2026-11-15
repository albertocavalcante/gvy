// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.completion

import com.github.albertocavalcante.gvy.common.text.GroovyCodeCleaner
import com.github.albertocavalcante.gvy.jenkins.metadata.declarative.DeclarativePipelineSchema
import com.github.albertocavalcante.gvy.source.Utf16LineIndex

/** The kind of Jenkins source at a cursor position. */
enum class JenkinsRegion {
    DECLARATIVE_STRUCTURE,
    EXECUTABLE_GROOVY,
    UNCERTAIN,
}

/** Context derived from one immutable source snapshot. */
data class JenkinsPositionContext(
    val region: JenkinsRegion,
    val allowsGroovyStatements: Boolean = false,
    val enclosingBlocks: List<String> = emptyList(),
    val currentBlock: String? = null,
)

/** Holds the exact source used for all position queries; columns are UTF-16 code units. */
class JenkinsSourceContext(val source: String) {
    private val lineIndex = Utf16LineIndex(source)
    private val events: List<BraceEvent> by lazy { collectBraceEvents() }

    fun at(line: Int, character: Int): JenkinsPositionContext {
        if (!lineIndex.isValidPosition(line, character)) {
            return JenkinsPositionContext(JenkinsRegion.UNCERTAIN)
        }
        val offset = lineIndex.offsetOf(line, character)
        val eventIndex = events.binarySearch { it.offset.compareTo(offset) }
        val priorIndex = if (eventIndex >= 0) eventIndex - 1 else -eventIndex - 2
        val stack = buildList {
            var frame = events.getOrNull(priorIndex)?.frame
            while (frame != null) {
                add(frame.name)
                frame = frame.parent
            }
        }.asReversed()
        val state = classify(stack)
        val enclosing = stack.filterNotNull()
        return JenkinsPositionContext(
            region = state.region,
            allowsGroovyStatements = state.allowsGroovy && state.region == JenkinsRegion.EXECUTABLE_GROOVY,
            enclosingBlocks = enclosing,
            currentBlock = enclosing.asReversed().firstOrNull(DeclarativePipelineSchema::containsBlock),
        )
    }

    private fun classify(stack: List<String?>): RegionState {
        var state = RegionState(JenkinsRegion.EXECUTABLE_GROOVY, allowsGroovy = true)
        var parent: String? = null
        stack.forEachIndexed { index, block ->
            state = when {
                index == 0 && block == "pipeline" -> RegionState(JenkinsRegion.DECLARATIVE_STRUCTURE, false)
                state.region == JenkinsRegion.DECLARATIVE_STRUCTURE -> classifyDeclarative(block, parent, state)
                state.region == JenkinsRegion.EXECUTABLE_GROOVY && !state.allowsGroovy && block == "script" ->
                    state.copy(allowsGroovy = true)
                else -> state
            }
            parent = block
        }
        return state
    }

    private fun classifyDeclarative(block: String?, parent: String?, previous: RegionState): RegionState = when {
        block == null -> RegionState(JenkinsRegion.UNCERTAIN, previous.allowsGroovy)
        block == "steps" || (parent == "post" && block in POST_CONDITIONS) ->
            RegionState(JenkinsRegion.EXECUTABLE_GROOVY, false)
        block == "expression" && parent in WHEN_BLOCKS -> RegionState(JenkinsRegion.EXECUTABLE_GROOVY, true)
        block == "node" && parent == "agent" -> previous
        !DeclarativePipelineSchema.containsBlock(block) && block !in WHEN_BLOCKS ->
            RegionState(JenkinsRegion.UNCERTAIN, previous.allowsGroovy)
        else -> previous
    }

    private data class RegionState(val region: JenkinsRegion, val allowsGroovy: Boolean)

    private fun collectBraceEvents(): List<BraceEvent> {
        var frame: BraceFrame? = null
        val result = mutableListOf<BraceEvent>()
        // The cleaner recognizes LF line endings. Replacing CR preserves all source offsets, including CRLF.
        val cleaned = GroovyCodeCleaner.blankCommentsAndStrings(source.replace('\r', '\n'))
        val openings = JenkinsContextDetector.blockOpeningsByBrace(cleaned)
        cleaned.forEachIndexed { offset, char ->
            when (char) {
                '{' -> frame = BraceFrame(openings[offset], frame)
                '}' -> frame = frame?.parent
                else -> return@forEachIndexed
            }
            result.add(BraceEvent(offset, frame))
        }
        return result
    }

    private class BraceFrame(val name: String?, val parent: BraceFrame?)

    private class BraceEvent(val offset: Int, val frame: BraceFrame?)

    private companion object {
        val POST_CONDITIONS = setOf(
            "always", "success", "failure", "unstable", "changed", "fixed", "regression", "aborted",
            "notBuilt", "unsuccessful", "cleanup",
        )
        val WHEN_BLOCKS = setOf("when", "allOf", "anyOf", "not")
    }
}

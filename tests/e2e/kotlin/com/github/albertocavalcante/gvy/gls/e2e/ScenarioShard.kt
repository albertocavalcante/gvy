// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

/**
 * One of [count] disjoint slices of the scenarios, chosen by a stable hash of the scenario name,
 * so CI can run the suite as parallel jobs (`-Dgroovy.lsp.e2e.shard=<index>/<count>`). Every
 * scenario lands in exactly one shard; without the property, all scenarios run.
 */
data class ScenarioShard(val index: Int, val count: Int) {
    init {
        require(count >= 1 && index in 0 until count) { "Invalid shard $index/$count" }
    }

    fun includes(scenarioName: String): Boolean = Math.floorMod(scenarioName.hashCode(), count) == index

    companion object {
        val ALL = ScenarioShard(0, 1)

        /** Parses `<index>/<count>`; blank means [ALL]. */
        fun parse(value: String?): ScenarioShard {
            if (value.isNullOrBlank()) return ALL
            val parts = value.split('/')
            require(parts.size == 2) { "Expected groovy.lsp.e2e.shard=<index>/<count>, got '$value'" }
            return ScenarioShard(parts[0].trim().toInt(), parts[1].trim().toInt())
        }
    }
}

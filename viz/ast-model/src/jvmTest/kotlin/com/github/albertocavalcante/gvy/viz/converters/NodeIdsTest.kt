// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.converters

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NodeIdsTest {
    @Test
    fun `numbers nodes from one, in the order they are asked for`() {
        val ids = NodeIds()
        assertEquals(listOf("node-1", "node-2", "node-3"), List(3) { ids.next() })
    }
}

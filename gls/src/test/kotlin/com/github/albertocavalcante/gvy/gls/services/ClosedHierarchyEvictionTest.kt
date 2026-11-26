// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClosedHierarchyEvictionTest {
    enum class Direction { INCOMING, OUTGOING }

    @ParameterizedTest
    @EnumSource(Direction::class)
    fun `unchanged closed item survives eviction and collection of its file state`(
        direction: Direction,
        @TempDir root: Path,
    ) {
        val path = root.resolve("Chain.groovy")
        Files.writeString(
            path,
            "class Chain {\n def before() { middle() }\n def middle() { after() }\n def after() {}\n}\n",
        )
        val other = root.resolve("Other.groovy")
        Files.writeString(other, "class Other { def unrelated() {} }")
        val compilation = GroovyCompilationService(compilationCacheMaxEntries = 1)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val service = GroovyTextDocumentService(scope, compilation)
            fun prepare(file: Path, position: Position) = service.prepareCallHierarchy(
                CallHierarchyPrepareParams(TextDocumentIdentifier(file.toUri().toString()), position),
            ).get(10, TimeUnit.SECONDS).single()

            val original = prepare(path, Position(2, 6))
            assertEquals("middle", original.name)
            val cache = compilation.compilationCacheService
            assertNotNull(cache.getOwned(path.toUri()))
            assertEquals("unrelated", prepare(other, Position(0, 19)).name)
            assertNull(cache.getOwned(path.toUri()))
            assertNull(compilation.getCompiledSnapshot(path.toUri()))
            cache.ownership.clearUnreferencedStateForTesting(path.toUri())

            when (direction) {
                Direction.INCOMING -> assertEquals(
                    listOf("before"),
                    service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(original))
                        .get(10, TimeUnit.SECONDS).map { it.from.name },
                )
                Direction.OUTGOING -> assertEquals(
                    listOf("after"),
                    service.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(original))
                        .get(10, TimeUnit.SECONDS).map { it.to.name },
                )
            }
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}

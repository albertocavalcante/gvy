package com.github.albertocavalcante.gvy.semantics.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Consistency of the indexes under concurrent writers and readers.
 *
 * Each stress test asserts an invariant that the previous implementation violated: index entries
 * lost by a computeIfAbsent/remove race, exceptions from an access-order LRU mutated under a read
 * lock, and index entries that no longer match the stored document.
 */
class GroovySemanticDBConcurrencyTest {

    private val shared = "com/example/Shared#"

    private fun uri(i: Int) = URI.create("file:///File$i.groovy")

    private fun docDefining(uri: URI, vararg symbols: String) = SemanticDocument(
        uri = uri,
        symbols = symbols.map { SymbolInfo(it, SymbolKind.CLASS, Range(0, 0, 0, 10), it, owner = null) },
        occurrences = symbols.map { SymbolOccurrence(it, Range(0, 0, 0, 10), OccurrenceRole.DEFINITION) },
    )

    /** Runs [threads] copies of [body] released together; returns everything they threw. */
    private fun race(threads: Int, body: (thread: Int) -> Unit): List<Throwable> {
        val start = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) { t ->
                pool.execute {
                    start.await()
                    try {
                        body(t)
                    } catch (e: Throwable) {
                        errors += e
                    }
                }
            }
            start.countDown()
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers did not finish")
        }
        return errors
    }

    @Test
    fun `definition lookup never returns a removed file while another file still defines the symbol`() {
        val db = GroovySemanticDB()
        db.updateDocument(uri(1), docDefining(uri(1), shared))
        db.updateDocument(uri(2), docDefining(uri(2), shared))
        assertEquals(uri(1), db.findSymbolDefinition(shared)?.first)

        db.removeDocument(uri(1))

        assertEquals(uri(2), db.findSymbolDefinition(shared)?.first)
    }

    @Test
    fun `concurrent writers on different files never lose index entries`() {
        // Two writers per symbol, one file each: the symbol's entry list keeps emptying and refilling,
        // which is where an add can land on a list that a concurrent remove just dropped from the index.
        val db = GroovySemanticDB()
        val threads = 16
        val symbolOf = { t: Int -> "com/example/Pair${t / 2}#" }

        val lost = AtomicInteger()
        val errors = race(threads) { t ->
            val u = uri(t)
            repeat(20_000) {
                db.updateDocument(u, docDefining(u, symbolOf(t)))
                // Only this thread removes u's entries, so they must be visible until it does.
                if (db.findAllSymbolDefinitions(symbolOf(t)).none { it.first == u }) lost.incrementAndGet()
                if (db.findAllOccurrences(symbolOf(t)).none { it.first == u }) lost.incrementAndGet()
                db.removeDocument(u)
            }
            db.updateDocument(u, docDefining(u, symbolOf(t)))
        }

        assertEquals(emptyList<Throwable>(), errors)
        assertEquals(0, lost.get(), "index entries missing right after their own update")
        (0 until threads / 2).forEach { p ->
            val symbol = "com/example/Pair$p#"
            assertEquals(2, db.findAllSymbolDefinitions(symbol).size, "symbol index entries for $symbol")
            assertEquals(2, db.findAllOccurrences(symbol).size, "occurrence index entries for $symbol")
        }
    }

    @Test
    fun `lookups racing many readers and a writer never throw`() {
        val db = GroovySemanticDB()
        val stable = (0 until 2_000).map { "com/example/Stable$it#" }
        db.updateDocument(uri(0), docDefining(uri(0), *stable.toTypedArray()))
        val flapping = "com/example/Flapping#"
        val readers = 8

        val errors = race(readers + 1) { t ->
            if (t == readers) {
                repeat(50_000) {
                    db.updateDocument(uri(1), docDefining(uri(1), flapping))
                    db.removeDocument(uri(1))
                }
            } else {
                repeat(200_000) { i ->
                    db.findSymbolDefinition(stable[(i * 7 + t) % stable.size])
                    db.findSymbolDefinition(flapping)
                }
            }
        }

        assertEquals(emptyList<Throwable>(), errors)
        stable.forEach { assertEquals(uri(0), db.findSymbolDefinition(it)?.first, "definition of $it") }
    }

    @Test
    fun `concurrent updates of the same file leave indexes matching the stored document`() {
        val db = GroovySemanticDB()
        val target = uri(0)
        val variants = (0 until 8).map { "com/example/Variant$it#" }

        val errors = race(variants.size) { t ->
            repeat(2_000) { db.updateDocument(target, docDefining(target, variants[t])) }
        }

        assertEquals(emptyList<Throwable>(), errors)
        val stored = db.getDocument(target)!!.symbols.map { it.symbol }.toSet()
        val indexed = variants.filter { v -> db.findAllSymbolDefinitions(v).any { it.first == target } }.toSet()
        assertEquals(stored, indexed, "indexed symbols for $target")
        variants.forEach { v -> assertTrue(db.findAllSymbolDefinitions(v).size <= 1, "duplicate entries for $v") }
    }
}

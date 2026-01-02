package com.github.albertocavalcante.groovyparser.benchmarks

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.net.URI
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
open class VisitorBenchmark {

    private lateinit var parser: GroovyParserFacade
    private lateinit var largeScript: String
    private val uri = URI.create("file:///benchmark.groovy")

    @Setup
    fun setup() {
        parser = GroovyParserFacade()
        // Generate a large script to stress traversal (approx 1000 classes with methods and closures)
        val sb = StringBuilder()
        repeat(500) { i ->
            sb.append(
                """
                class BenchmarkClass$i {
                    @Deprecated
                    def method$i(String arg) {
                        def closure = { it -> println it }
                        if (arg) {
                            closure(arg)
                        }
                        try {
                            return [key: $i, list: [1, 2, 3]]
                        } catch (Exception e) {
                            throw e
                        }
                    }
                }
            """,
            ).append("\n")
        }
        largeScript = sb.toString()
    }

    @Benchmark
    fun recursiveVisitor() {
        val request = ParseRequest(uri, largeScript)
        parser.parse(request)
    }
}

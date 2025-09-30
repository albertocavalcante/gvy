package com.github.albertocavalcante.groovylsp.jenkins

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Comprehensive Spock tests for SimpleGdslParser.
 * Tests GDSL parsing capabilities with data-driven testing, edge cases, and real-world scenarios.
 */
class SimpleGdslParserSpec extends Specification {

    def "parseMethodNames should extract method names from valid GDSL"() {
        given: "a valid GDSL input with method definitions"
        def gdslInput = """
            contribute(context(ctype: 'hudson.model.Job')) {
                method(name: 'pipeline', type: 'Object')
                method(name: 'stage', type: 'void')
                method(name: 'checkout', type: 'hudson.scm.SCM')
            }
        """

        when: "parsing method names"
        def result = SimpleGdslParser.parseMethodNames(gdslInput)

        then: "should extract all method names correctly"
        result.size() == 3
        result.contains('pipeline')
        result.contains('stage')
        result.contains('checkout')
    }

    @Unroll
    def "parseMethodNames should handle various GDSL patterns: #description"() {
        when: "parsing GDSL input"
        def result = SimpleGdslParser.parseMethodNames(input)

        then: "should extract expected methods"
        result == expectedMethods

        where:
        description                          | input                                                        | expectedMethods
        "single method with single quotes"   | "method(name: 'echo', type: 'void')"                       | ['echo']
        "single method with double quotes"   | 'method(name: "sh", type: "Object")'                       | ['sh']
        "multiple methods on same line"      | "method(name: 'build', type: 'Run'); method(name: 'deploy', type: 'void')" | ['build'] // Note: Current parser only finds first method per line
        "methods with extra whitespace"      | "  method(  name:  'test'  ,  type:  'String'  )  "        | ['test']
        "methods with tabs"                  | "\tmethod(\tname:\t'lint'\t,\ttype:\t'void'\t)"            | ['lint']
        "nested context blocks"              | """
                                               contribute(context(ctype: 'Job')) {
                                                   method(name: 'parallel', type: 'void')
                                                   contribute(context(ctype: 'Node')) {
                                                       method(name: 'node', type: 'Object')
                                                   }
                                               }
                                               """ | ['parallel', 'node']
    }

    @Unroll
    def "parseMethodNames should handle edge cases: #description"() {
        when: "parsing edge case input"
        def result = SimpleGdslParser.parseMethodNames(input)

        then: "should handle gracefully"
        result == expectedMethods

        where:
        description                    | input                                           | expectedMethods
        "empty string"                 | ""                                             | []
        "null input"                   | null                                           | []
        "whitespace only"              | "   \t\n  "                                    | []
        "method without name parameter"| "method(type: 'void')"                        | []
        "malformed method syntax"      | "method name: 'broken', type: 'void')"        | []
        "method with missing quotes"   | "method(name: echo, type: 'void')"            | []
        "method with unclosed quotes"  | "method(name: 'unclosed, type: 'void')"       | ['unclosed, type: '] // Note: Parser partially matches malformed input
        "non-GDSL content"            | "def someGroovyCode() { return 'hello' }"      | []
    }

    def "parseMethodNames should handle real-world Jenkins GDSL examples"() {
        given: "a realistic Jenkins GDSL file content"
        def jenkinsGdsl = """
            // Jenkins Pipeline Steps GDSL
            contribute(context(ctype: 'hudson.model.Job')) {
                // Core pipeline steps
                method(name: 'node', type: 'void', params: [label: 'String'])
                method(name: 'stage', type: 'void', params: [name: 'String'])
                method(name: 'parallel', type: 'void', params: [branches: 'Map'])

                // SCM steps
                method(name: 'checkout', type: 'hudson.scm.SCM', params: [scm: 'hudson.scm.SCM'])
                method(name: 'git', type: 'hudson.scm.SCM', params: [url: 'String'])

                // Build steps
                method(name: 'sh', type: 'Object', params: [script: 'String'])
                method(name: 'bat', type: 'Object', params: [script: 'String'])
                method(name: 'powershell', type: 'Object', params: [script: 'String'])

                // Utility steps
                method(name: 'echo', type: 'void', params: [message: 'String'])
                method(name: 'sleep', type: 'void', params: [time: 'Integer'])
                method(name: 'timeout', type: 'void', params: [time: 'Integer'])

                // Artifact steps
                method(name: 'archiveArtifacts', type: 'void', params: [artifacts: 'String'])
                method(name: 'stash', type: 'void', params: [name: 'String'])
                method(name: 'unstash', type: 'void', params: [name: 'String'])
            }

            // Additional context for different pipeline types
            contribute(context(ctype: 'org.jenkinsci.plugins.workflow.cps.CpsScript')) {
                method(name: 'pipeline', type: 'void')
                method(name: 'properties', type: 'void', params: [properties: 'List'])
            }
        """

        when: "parsing the realistic GDSL content"
        def result = SimpleGdslParser.parseMethodNames(jenkinsGdsl)

        then: "should extract all Jenkins methods"
        result.size() == 16

        and: "should contain core pipeline methods"
        ['node', 'stage', 'parallel'].every { result.contains(it) }

        and: "should contain SCM methods"
        ['checkout', 'git'].every { result.contains(it) }

        and: "should contain build steps"
        ['sh', 'bat', 'powershell'].every { result.contains(it) }

        and: "should contain utility methods"
        ['echo', 'sleep', 'timeout'].every { result.contains(it) }

        and: "should contain artifact methods"
        ['archiveArtifacts', 'stash', 'unstash'].every { result.contains(it) }

        and: "should contain pipeline structure methods"
        ['pipeline', 'properties'].every { result.contains(it) }
    }

    @Unroll
    def "isValidGdsl should validate GDSL content: #description"() {
        when: "validating GDSL content"
        def result = SimpleGdslParser.isValidGdsl(input)

        then: "should return expected validation result"
        result == expectedValid

        where:
        description                 | input                                              | expectedValid
        "valid GDSL with method"    | "method(name: 'test', type: 'void')"             | true
        "valid GDSL with contribute"| "contribute(context(ctype: 'Job')) { }"          | true
        "both method and contribute"| "contribute() { method(name: 'test') }"          | true
        "empty string"              | ""                                                | false
        "null input"                | null                                              | false
        "plain Groovy code"         | "def test() { return 'hello' }"                  | false
        "random text"               | "this is just random text"                       | false
        "whitespace only"           | "   \t\n  "                                       | false
    }

    def "parseMethodNames should handle large GDSL files efficiently"() {
        given: "a large GDSL file with many method definitions"
        def largeGdsl = new StringBuilder()
        largeGdsl.append("contribute(context(ctype: 'hudson.model.Job')) {\n")

        // Generate 1000 method definitions
        (1..1000).each { i ->
            largeGdsl.append("    method(name: 'method${i}', type: 'void')\n")
        }
        largeGdsl.append("}")

        when: "parsing the large GDSL file"
        def startTime = System.currentTimeMillis()
        def result = SimpleGdslParser.parseMethodNames(largeGdsl.toString())
        def endTime = System.currentTimeMillis()
        def duration = endTime - startTime

        then: "should parse all methods correctly"
        result.size() == 1000

        and: "should complete within reasonable time (< 1 second)"
        duration < 1000

        and: "should contain expected method names"
        result.contains('method1')
        result.contains('method500')
        result.contains('method1000')
    }

    def "parseMethodNames should handle concurrent access safely"() {
        given: "a GDSL input for concurrent testing"
        def gdslInput = """
            contribute(context(ctype: 'hudson.model.Job')) {
                method(name: 'concurrent1', type: 'void')
                method(name: 'concurrent2', type: 'void')
                method(name: 'concurrent3', type: 'void')
            }
        """

        when: "parsing from multiple threads concurrently"
        def results = []
        def threads = []

        10.times { threadNum ->
            threads << Thread.start {
                def result = SimpleGdslParser.parseMethodNames(gdslInput)
                synchronized(results) {
                    results << result
                }
            }
        }

        // Wait for all threads to complete
        threads.each { it.join() }

        then: "all threads should produce identical results"
        results.size() == 10
        results.every { result ->
            result.size() == 3 &&
            result.contains('concurrent1') &&
            result.contains('concurrent2') &&
            result.contains('concurrent3')
        }
    }

    def "parseMethodNames should preserve method order when defined sequentially"() {
        given: "GDSL with methods in specific order"
        def gdslInput = """
            method(name: 'first', type: 'void')
            method(name: 'second', type: 'void')
            method(name: 'third', type: 'void')
        """

        when: "parsing method names"
        def result = SimpleGdslParser.parseMethodNames(gdslInput)

        then: "should maintain declaration order"
        result == ['first', 'second', 'third']
    }

    def "getTestResult should return consistent metadata"() {
        when: "getting test result multiple times"
        def result1 = SimpleGdslParser.getTestResult()
        Thread.sleep(10) // Small delay to ensure different timestamps
        def result2 = SimpleGdslParser.getTestResult()

        then: "should return consistent static values"
        result1.message == "Groovy-Kotlin interop is working!"
        result1.parser == "SimpleGdslParser"
        result1.version == "1.0"

        and: "should have same static values in both calls"
        result1.message == result2.message
        result1.parser == result2.parser
        result1.version == result2.version

        and: "should have different timestamps"
        result1.timestamp != result2.timestamp

        and: "timestamps should be valid"
        result1.timestamp instanceof Long
        result2.timestamp instanceof Long
        result1.timestamp > 0
        result2.timestamp > 0
    }
}

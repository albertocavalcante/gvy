package com.example

import spock.lang.Specification

class CalculatorSpec extends Specification {
    def "should add two numbers"() {
        given: "two numbers"
        def a = 1
        def b = 2

        when: "adding them"
        def result = a + b

        then: "the result is correct"
        result == 3
    }

    def "should subtract numbers"() {
        expect:
        5 - 3 == 2
    }
}

package com.github.albertocavalcante.groovyspock

/**
 * Represents a Spock feature method (test method).
 *
 * @property name The method name (feature description, e.g., "should calculate sum")
 * @property line The 1-indexed line number where the method starts
 */
data class SpockFeatureMethod(val name: String, val line: Int)

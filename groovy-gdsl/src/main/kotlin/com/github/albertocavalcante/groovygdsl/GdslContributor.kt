package com.github.albertocavalcante.groovygdsl

/**
 * Delegate for the contributor closure.
 * Captures method and property definitions.
 */
class GdslContributor(val context: GdslContext) {

    fun property(args: Map<String, Any>) {
        // Capture property definition
        // name: String, type: String, doc: String
    }

    fun method(args: Map<String, Any>) {
        // Capture method definition
        // name: String, type: String, params: Map, doc: String
    }

    fun delegatesTo(type: Any) {
        // Capture delegation
    }

    fun findClass(className: String): String = className
}

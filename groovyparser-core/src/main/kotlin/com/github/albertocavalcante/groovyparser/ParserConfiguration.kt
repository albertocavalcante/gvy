package com.github.albertocavalcante.groovyparser

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Configuration for the Groovy parser.
 * Uses a builder-style fluent API for configuration.
 */
class ParserConfiguration {
    /** The language level to parse for */
    var languageLevel: GroovyLanguageLevel = GroovyLanguageLevel.POPULAR
        private set

    /** Tab size for column calculations */
    var tabSize: Int = 1
        private set

    /** Character encoding for reading source files */
    var characterEncoding: Charset = StandardCharsets.UTF_8
        private set

    /** Whether to store tokens in the AST */
    var storeTokens: Boolean = true
        private set

    /** Whether to attribute comments to AST nodes */
    var attributeComments: Boolean = true
        private set

    /**
     * Sets the language level.
     */
    fun setLanguageLevel(level: GroovyLanguageLevel): ParserConfiguration {
        this.languageLevel = level
        return this
    }

    /**
     * Sets the tab size for column calculations.
     */
    fun setTabSize(tabSize: Int): ParserConfiguration {
        this.tabSize = tabSize
        return this
    }

    /**
     * Sets the character encoding for reading source files.
     */
    fun setCharacterEncoding(encoding: Charset): ParserConfiguration {
        this.characterEncoding = encoding
        return this
    }

    /**
     * Sets whether to store tokens in the AST.
     */
    fun setStoreTokens(storeTokens: Boolean): ParserConfiguration {
        this.storeTokens = storeTokens
        return this
    }

    /**
     * Sets whether to attribute comments to AST nodes.
     */
    fun setAttributeComments(attributeComments: Boolean): ParserConfiguration {
        this.attributeComments = attributeComments
        return this
    }
}

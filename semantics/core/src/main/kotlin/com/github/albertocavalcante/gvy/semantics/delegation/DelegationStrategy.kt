package com.github.albertocavalcante.gvy.semantics.delegation

/**
 * Groovy closure delegation strategies.
 *
 * Corresponds to Closure.OWNER_FIRST, DELEGATE_FIRST, etc.
 * See: https://docs.groovy-lang.org/latest/html/api/groovy/lang/Closure.html
 */
enum class DelegationStrategy(val value: Int) {
    /**
     * With this resolveStrategy set the closure will attempt to resolve property
     * references and methods to the owner first, then the delegate.
     */
    OWNER_FIRST(0),

    /**
     * With this resolveStrategy set the closure will attempt to resolve property
     * references and methods to the delegate first, then the owner.
     * This is the default strategy.
     */
    DELEGATE_FIRST(1),

    /**
     * With this resolveStrategy set the closure will resolve property references
     * and methods to the owner only and not call the delegate at all.
     */
    OWNER_ONLY(2),

    /**
     * With this resolveStrategy set the closure will resolve property references
     * and methods to the delegate only and entirely bypass the owner.
     */
    DELEGATE_ONLY(3),

    /**
     * With this resolveStrategy set the closure will resolve property references
     * to itself and go through the usual MetaClass look-up process.
     */
    TO_SELF(4),
    ;

    companion object {
        /**
         * Default strategy used by Groovy closures.
         */
        val DEFAULT = OWNER_FIRST

        /**
         * Get strategy from Groovy's integer constant.
         */
        fun fromValue(value: Int): DelegationStrategy = entries.find { it.value == value } ?: DEFAULT
    }
}

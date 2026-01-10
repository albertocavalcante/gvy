package com.github.albertocavalcante.testing.mocks

import io.mockk.mockk

/**
 * Standardized mock factory patterns using MockK.
 *
 * This object provides factory methods for creating commonly-used mocks
 * with sensible defaults for testing. All mocks are created as relaxed
 * by default to reduce test boilerplate.
 *
 * Example usage:
 * ```kotlin
 * val compilationService = MockPatterns.compilationService()
 * val documentProvider = MockPatterns.documentProvider()
 * ```
 */
object MockPatterns {

    /**
     * Create a relaxed mock of any type.
     *
     * Relaxed mocks automatically return default values for all methods,
     * which reduces the need for extensive stubbing in tests.
     *
     * @param T The type to mock
     * @param name Optional name for the mock (useful for debugging)
     * @param relaxed Whether to create a relaxed mock (default: true)
     * @param relaxUnitFun Whether to relax unit functions (default: true)
     * @return A mock instance of type T
     */
    inline fun <reified T : Any> relaxedMock(
        name: String? = null,
        relaxed: Boolean = true,
        relaxUnitFun: Boolean = true,
    ): T = mockk(
        name = name,
        relaxed = relaxed,
        relaxUnitFun = relaxUnitFun,
    )

    /**
     * Create a strict mock of any type.
     *
     * Strict mocks require explicit stubbing for all method calls.
     * Use this when you want to enforce that only expected interactions occur.
     *
     * @param T The type to mock
     * @param name Optional name for the mock (useful for debugging)
     * @return A strict mock instance of type T
     */
    inline fun <reified T : Any> strictMock(name: String? = null): T = mockk(
        name = name,
        relaxed = false,
        relaxUnitFun = false,
    )

    /**
     * Create a spy of a real object.
     *
     * Spies delegate to the real object by default but allow
     * selective stubbing of specific methods.
     *
     * @param T The type to spy on
     * @param obj The real object to spy on
     * @param name Optional name for the spy (useful for debugging)
     * @param recordPrivateCalls Whether to record private method calls (default: false)
     * @return A spy instance wrapping the real object
     */
    inline fun <reified T : Any> spy(obj: T, name: String? = null, recordPrivateCalls: Boolean = false): T =
        io.mockk.spyk(
            objToCopy = obj,
            name = name,
            recordPrivateCalls = recordPrivateCalls,
        )

    /**
     * Create a compilation service mock.
     *
     * This is a convenience method for creating a commonly-used mock
     * in LSP-related tests. Returns a relaxed mock by default.
     *
     * @param relaxed Whether to create a relaxed mock (default: true)
     * @return A mock compilation service
     */
    inline fun <reified T : Any> compilationService(relaxed: Boolean = true): T = mockk(relaxed = relaxed)

    /**
     * Create a document provider mock.
     *
     * This is a convenience method for creating a commonly-used mock
     * in LSP-related tests. Returns a relaxed mock by default.
     *
     * @param relaxed Whether to create a relaxed mock (default: true)
     * @return A mock document provider
     */
    inline fun <reified T : Any> documentProvider(relaxed: Boolean = true): T = mockk(relaxed = relaxed)

    /**
     * Create a language client mock.
     *
     * This is a convenience method for creating a commonly-used mock
     * in LSP server tests. Returns a relaxed mock by default.
     *
     * @param relaxed Whether to create a relaxed mock (default: true)
     * @return A mock language client
     */
    inline fun <reified T : Any> languageClient(relaxed: Boolean = true): T = mockk(relaxed = relaxed)

    /**
     * Create a type resolver mock.
     *
     * This is a convenience method for creating a commonly-used mock
     * in semantic analysis tests. Returns a relaxed mock by default.
     *
     * @param relaxed Whether to create a relaxed mock (default: true)
     * @return A mock type resolver
     */
    inline fun <reified T : Any> typeResolver(relaxed: Boolean = true): T = mockk(relaxed = relaxed)

    /**
     * Create a parser facade mock.
     *
     * This is a convenience method for creating a commonly-used mock
     * in parser-related tests. Returns a relaxed mock by default.
     *
     * @param relaxed Whether to create a relaxed mock (default: true)
     * @return A mock parser facade
     */
    inline fun <reified T : Any> parserFacade(relaxed: Boolean = true): T = mockk(relaxed = relaxed)
}

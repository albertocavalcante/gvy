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
 * val compilationService = MockPatterns.relaxedMock<CompilationService>()
 * val documentProvider: DocumentProvider = MockPatterns.relaxedMock(name = "docProvider")
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
     * @param objToCopy The real object to spy on
     * @param name Optional name for the spy (useful for debugging)
     * @param recordPrivateCalls Whether to record private method calls (default: false)
     * @return A spy instance wrapping the real object
     */
    inline fun <reified T : Any> spy(objToCopy: T, name: String? = null, recordPrivateCalls: Boolean = false): T =
        io.mockk.spyk(
            objToCopy = objToCopy,
            name = name,
            recordPrivateCalls = recordPrivateCalls,
        )
}

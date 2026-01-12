package com.github.albertocavalcante.groovyjenkins.extraction

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Tests for FunctionNameExtractor.
 *
 * These tests verify that the extractor correctly reads the return value
 * of getFunctionName() methods from bytecode using ASM.
 *
 * @see <a href="https://github.com/albertocavalcante/gvy/issues/834">Issue #834</a>
 */
class FunctionNameExtractorTest {

    private val extractor = FunctionNameExtractor()

    @Nested
    inner class `Bytecode Extraction` {

        @Test
        fun `extracts function name from simple return statement`() {
            // Generate bytecode for: public String getFunctionName() { return "echo"; }
            val bytecode = generateDescriptorClass("echo")

            val result = extractor.extractFromBytecode(bytecode, "TestDescriptor")

            assertThat(result).isEqualTo("echo")
        }

        @Test
        fun `extracts function name with different values`() {
            // Test various step names
            listOf("sh", "bat", "readFile", "writeFile", "timeout", "parallel").forEach { stepName ->
                val bytecode = generateDescriptorClass(stepName)

                val result = extractor.extractFromBytecode(bytecode, "Test")

                assertThat(result)
                    .describedAs("Expected to extract '$stepName' from bytecode")
                    .isEqualTo(stepName)
            }
        }

        @Test
        fun `handles empty string return value`() {
            val bytecode = generateDescriptorClass("")

            val result = extractor.extractFromBytecode(bytecode, "Test")

            assertThat(result).isEqualTo("")
        }

        @Test
        fun `handles special characters in step name`() {
            val bytecode = generateDescriptorClass("step-with-dashes_and_underscores")

            val result = extractor.extractFromBytecode(bytecode, "Test")

            assertThat(result).isEqualTo("step-with-dashes_and_underscores")
        }
    }

    @Nested
    inner class `Error Handling` {

        @Test
        fun `returns null for class without getFunctionName method`() {
            // Generate a class without getFunctionName method
            val bytecode = generateClassWithoutMethod()

            val result = extractor.extractFromBytecode(bytecode, "TestClass")

            assertThat(result).isNull()
        }

        @Test
        fun `returns null for invalid bytecode`() {
            val invalidBytecode = byteArrayOf(0, 1, 2, 3)

            val result = extractor.extractFromBytecode(invalidBytecode, "InvalidClass")

            assertThat(result).isNull()
        }

        @Test
        fun `returns null for empty bytecode`() {
            val result = extractor.extractFromBytecode(byteArrayOf(), "EmptyClass")

            assertThat(result).isNull()
        }
    }

    @Nested
    inner class `Determinism` {

        @Test
        fun `extracting same bytecode produces identical results`() {
            val bytecode = generateDescriptorClass("testStep")

            val result1 = extractor.extractFromBytecode(bytecode, "Test")
            val result2 = extractor.extractFromBytecode(bytecode, "Test")

            assertThat(result1).isEqualTo(result2)
        }
    }

    /**
     * Generates bytecode for a minimal class with a getFunctionName() method
     * that returns the specified string constant.
     *
     * Equivalent to:
     * ```java
     * public class TestDescriptor {
     *     public String getFunctionName() {
     *         return "<stepName>";
     *     }
     * }
     * ```
     */
    private fun generateDescriptorClass(stepName: String): ByteArray {
        val classWriter = ClassWriter(0)

        // Define class: public class TestDescriptor
        classWriter.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            "TestDescriptor",
            null,
            "java/lang/Object",
            null,
        )

        // Generate getFunctionName method
        val methodVisitor = classWriter.visitMethod(
            Opcodes.ACC_PUBLIC,
            "getFunctionName",
            "()Ljava/lang/String;",
            null,
            null,
        )
        methodVisitor.visitCode()
        methodVisitor.visitLdcInsn(stepName) // LDC "stepName"
        methodVisitor.visitInsn(Opcodes.ARETURN) // ARETURN
        methodVisitor.visitMaxs(1, 1)
        methodVisitor.visitEnd()

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }

    /**
     * Generates bytecode for a class without getFunctionName method.
     */
    private fun generateClassWithoutMethod(): ByteArray {
        val classWriter = ClassWriter(0)

        classWriter.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            "TestClass",
            null,
            "java/lang/Object",
            null,
        )

        // Only generate a default constructor, no getFunctionName
        val constructor = classWriter.visitMethod(
            Opcodes.ACC_PUBLIC,
            "<init>",
            "()V",
            null,
            null,
        )
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "java/lang/Object",
            "<init>",
            "()V",
            false,
        )
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(1, 1)
        constructor.visitEnd()

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }
}

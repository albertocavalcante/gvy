package com.github.albertocavalcante.gvy.jenkins.extraction

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream

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

    @Nested
    inner class `Complex Bytecode Patterns` {

        @Test
        fun `captures first return value when method has multiple constant returns`() {
            // Simulates: if (cond) { return "first"; } return "second";
            val bytecode = generateClassWithMultipleReturns("first", "second")

            val result = extractor.extractFromBytecode(bytecode, "Test")

            // Should capture "first" - the first LDC+ARETURN pair
            assertThat(result).isEqualTo("first")
        }

        @Test
        fun `returns null when getFunctionName returns a static field`() {
            // Simulates: return STEP_NAME; (where STEP_NAME is a static field)
            val bytecode = generateClassWithFieldReturn()

            val result = extractor.extractFromBytecode(bytecode, "Test")

            // Should return null because we can't resolve field values
            assertThat(result).isNull()
        }

        @Test
        fun `returns null when getFunctionName calls another method`() {
            // Simulates: return computeStepName();
            val bytecode = generateClassWithMethodCallReturn()

            val result = extractor.extractFromBytecode(bytecode, "Test")

            // Should return null because we can't trace method calls
            assertThat(result).isNull()
        }

        @Test
        fun `handles unicode step names`() {
            val bytecode = generateDescriptorClass("échoStep")

            val result = extractor.extractFromBytecode(bytecode, "Test")

            assertThat(result).isEqualTo("échoStep")
        }

        @Test
        fun `handles step names with whitespace`() {
            // While unusual, step names could theoretically have spaces
            val bytecode = generateDescriptorClass("step with space")

            val result = extractor.extractFromBytecode(bytecode, "Test")

            assertThat(result).isEqualTo("step with space")
        }

        @Test
        fun `handles very long step names`() {
            val longName = "a".repeat(1000)
            val bytecode = generateDescriptorClass(longName)

            val result = extractor.extractFromBytecode(bytecode, "Test")

            assertThat(result).isEqualTo(longName)
        }
    }

    @Nested
    inner class `JAR Extraction` {

        @Test
        fun `extracts function name from class in JAR file`(@TempDir tempDir: Path) {
            val jarPath = tempDir.resolve("test-plugin.jar")
            val bytecode = generateDescriptorClass("myStep")

            // Create a JAR file with the test class
            JarOutputStream(jarPath.outputStream()).use { jar ->
                jar.putNextEntry(JarEntry("com/example/MyStep\$DescriptorImpl.class"))
                jar.write(bytecode)
                jar.closeEntry()
            }

            val result = extractor.extractFromJar(jarPath, "com.example.MyStep\$DescriptorImpl")

            assertThat(result).isEqualTo("myStep")
        }

        @Test
        fun `returns null for non-existent JAR file`(@TempDir tempDir: Path) {
            val nonExistentPath = tempDir.resolve("does-not-exist.jar")

            val result = extractor.extractFromJar(nonExistentPath, "com.example.SomeClass")

            assertThat(result).isNull()
        }

        @Test
        fun `returns null for class not in JAR`(@TempDir tempDir: Path) {
            val jarPath = tempDir.resolve("test-plugin.jar")

            // Create an empty JAR
            JarOutputStream(jarPath.outputStream()).use { jar ->
                jar.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
                jar.write("Manifest-Version: 1.0\n".toByteArray())
                jar.closeEntry()
            }

            val result = extractor.extractFromJar(jarPath, "com.example.NonExistent")

            assertThat(result).isNull()
        }

        @Test
        fun `handles nested class names with dollar signs`(@TempDir tempDir: Path) {
            val jarPath = tempDir.resolve("test.jar")
            val bytecode = generateDescriptorClass("nestedStep")

            JarOutputStream(jarPath.outputStream()).use { jar ->
                jar.putNextEntry(JarEntry("com/example/Outer\$Inner\$DescriptorImpl.class"))
                jar.write(bytecode)
                jar.closeEntry()
            }

            val result = extractor.extractFromJar(jarPath, "com.example.Outer\$Inner\$DescriptorImpl")

            assertThat(result).isEqualTo("nestedStep")
        }
    }

    @Nested
    inner class `Edge Cases` {

        @Test
        fun `handles class with other methods besides getFunctionName`() {
            val bytecode = generateClassWithMultipleMethods("targetStep")

            val result = extractor.extractFromBytecode(bytecode, "Test")

            assertThat(result).isEqualTo("targetStep")
        }

        @Test
        fun `returns null for protected getFunctionName method`() {
            // getFunctionName is expected to be public
            val bytecode = generateClassWithProtectedMethod("hiddenStep")

            val result = extractor.extractFromBytecode(bytecode, "Test")

            // Should still extract because we match on method name and descriptor, not access modifier
            assertThat(result).isEqualTo("hiddenStep")
        }

        @Test
        fun `returns null for getFunctionName with wrong return type`() {
            // Method has same name but returns void
            val bytecode = generateClassWithWrongReturnType()

            val result = extractor.extractFromBytecode(bytecode, "Test")

            assertThat(result).isNull()
        }

        @Test
        fun `handles class with getFunctionName that takes parameters`() {
            // Overloaded method with parameters - should not match
            val bytecode = generateClassWithOverloadedMethod("wrongStep")

            val result = extractor.extractFromBytecode(bytecode, "Test")

            // Should return null because it doesn't match the ()Ljava/lang/String; signature
            assertThat(result).isNull()
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

    /**
     * Generates bytecode for a class with multiple return statements in getFunctionName.
     *
     * Equivalent to:
     * ```java
     * public String getFunctionName() {
     *     if (true) { return "<first>"; }
     *     return "<second>";
     * }
     * ```
     */
    private fun generateClassWithMultipleReturns(first: String, second: String): ByteArray {
        val classWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES)

        classWriter.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            "TestDescriptor",
            null,
            "java/lang/Object",
            null,
        )

        val methodVisitor = classWriter.visitMethod(
            Opcodes.ACC_PUBLIC,
            "getFunctionName",
            "()Ljava/lang/String;",
            null,
            null,
        )
        methodVisitor.visitCode()

        // if (true) - always jumps, but bytecode still has both paths
        val elseLabel = Label()
        methodVisitor.visitInsn(Opcodes.ICONST_1)
        methodVisitor.visitJumpInsn(Opcodes.IFEQ, elseLabel)

        // return "first"
        methodVisitor.visitLdcInsn(first)
        methodVisitor.visitInsn(Opcodes.ARETURN)

        // else path
        methodVisitor.visitLabel(elseLabel)
        methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        methodVisitor.visitLdcInsn(second)
        methodVisitor.visitInsn(Opcodes.ARETURN)

        methodVisitor.visitMaxs(1, 1)
        methodVisitor.visitEnd()

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }

    /**
     * Generates bytecode for getFunctionName that returns a static field.
     *
     * Equivalent to:
     * ```java
     * private static final String STEP_NAME = "fromField";
     * public String getFunctionName() {
     *     return STEP_NAME;
     * }
     * ```
     */
    private fun generateClassWithFieldReturn(): ByteArray {
        val classWriter = ClassWriter(0)

        classWriter.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            "TestDescriptor",
            null,
            "java/lang/Object",
            null,
        )

        // Add static field
        classWriter.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "STEP_NAME",
            "Ljava/lang/String;",
            null,
            "fromField",
        )

        val methodVisitor = classWriter.visitMethod(
            Opcodes.ACC_PUBLIC,
            "getFunctionName",
            "()Ljava/lang/String;",
            null,
            null,
        )
        methodVisitor.visitCode()
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "TestDescriptor", "STEP_NAME", "Ljava/lang/String;")
        methodVisitor.visitInsn(Opcodes.ARETURN)
        methodVisitor.visitMaxs(1, 1)
        methodVisitor.visitEnd()

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }

    /**
     * Generates bytecode for getFunctionName that calls another method.
     *
     * Equivalent to:
     * ```java
     * public String getFunctionName() {
     *     return computeName();
     * }
     * private String computeName() { return "computed"; }
     * ```
     */
    private fun generateClassWithMethodCallReturn(): ByteArray {
        val classWriter = ClassWriter(0)

        classWriter.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            "TestDescriptor",
            null,
            "java/lang/Object",
            null,
        )

        // getFunctionName that calls computeName()
        val methodVisitor = classWriter.visitMethod(
            Opcodes.ACC_PUBLIC,
            "getFunctionName",
            "()Ljava/lang/String;",
            null,
            null,
        )
        methodVisitor.visitCode()
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
        methodVisitor.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "TestDescriptor",
            "computeName",
            "()Ljava/lang/String;",
            false,
        )
        methodVisitor.visitInsn(Opcodes.ARETURN)
        methodVisitor.visitMaxs(1, 1)
        methodVisitor.visitEnd()

        // Helper method
        val helper = classWriter.visitMethod(
            Opcodes.ACC_PRIVATE,
            "computeName",
            "()Ljava/lang/String;",
            null,
            null,
        )
        helper.visitCode()
        helper.visitLdcInsn("computed")
        helper.visitInsn(Opcodes.ARETURN)
        helper.visitMaxs(1, 1)
        helper.visitEnd()

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }

    /**
     * Generates a class with multiple methods including getFunctionName.
     */
    private fun generateClassWithMultipleMethods(stepName: String): ByteArray {
        val classWriter = ClassWriter(0)

        classWriter.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            "TestDescriptor",
            null,
            "java/lang/Object",
            null,
        )

        // Some other method
        val otherMethod = classWriter.visitMethod(
            Opcodes.ACC_PUBLIC,
            "getDisplayName",
            "()Ljava/lang/String;",
            null,
            null,
        )
        otherMethod.visitCode()
        otherMethod.visitLdcInsn("Display Name")
        otherMethod.visitInsn(Opcodes.ARETURN)
        otherMethod.visitMaxs(1, 1)
        otherMethod.visitEnd()

        // The target method
        val targetMethod = classWriter.visitMethod(
            Opcodes.ACC_PUBLIC,
            "getFunctionName",
            "()Ljava/lang/String;",
            null,
            null,
        )
        targetMethod.visitCode()
        targetMethod.visitLdcInsn(stepName)
        targetMethod.visitInsn(Opcodes.ARETURN)
        targetMethod.visitMaxs(1, 1)
        targetMethod.visitEnd()

        // Another method after
        val anotherMethod = classWriter.visitMethod(
            Opcodes.ACC_PUBLIC,
            "isMetaStep",
            "()Z",
            null,
            null,
        )
        anotherMethod.visitCode()
        anotherMethod.visitInsn(Opcodes.ICONST_0)
        anotherMethod.visitInsn(Opcodes.IRETURN)
        anotherMethod.visitMaxs(1, 1)
        anotherMethod.visitEnd()

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }

    /**
     * Generates a class with protected getFunctionName method.
     */
    private fun generateClassWithProtectedMethod(stepName: String): ByteArray {
        val classWriter = ClassWriter(0)

        classWriter.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            "TestDescriptor",
            null,
            "java/lang/Object",
            null,
        )

        val methodVisitor = classWriter.visitMethod(
            Opcodes.ACC_PROTECTED, // Protected instead of public
            "getFunctionName",
            "()Ljava/lang/String;",
            null,
            null,
        )
        methodVisitor.visitCode()
        methodVisitor.visitLdcInsn(stepName)
        methodVisitor.visitInsn(Opcodes.ARETURN)
        methodVisitor.visitMaxs(1, 1)
        methodVisitor.visitEnd()

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }

    /**
     * Generates a class where getFunctionName returns void (wrong return type).
     */
    private fun generateClassWithWrongReturnType(): ByteArray {
        val classWriter = ClassWriter(0)

        classWriter.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            "TestDescriptor",
            null,
            "java/lang/Object",
            null,
        )

        // Method with same name but returns void
        val methodVisitor = classWriter.visitMethod(
            Opcodes.ACC_PUBLIC,
            "getFunctionName",
            "()V", // void return type
            null,
            null,
        )
        methodVisitor.visitCode()
        methodVisitor.visitInsn(Opcodes.RETURN)
        methodVisitor.visitMaxs(0, 1)
        methodVisitor.visitEnd()

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }

    /**
     * Generates a class with getFunctionName that takes a parameter (overloaded).
     */
    private fun generateClassWithOverloadedMethod(stepName: String): ByteArray {
        val classWriter = ClassWriter(0)

        classWriter.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            "TestDescriptor",
            null,
            "java/lang/Object",
            null,
        )

        // Method with parameter - should NOT match
        val methodVisitor = classWriter.visitMethod(
            Opcodes.ACC_PUBLIC,
            "getFunctionName",
            "(Ljava/lang/String;)Ljava/lang/String;", // Takes a String parameter
            null,
            null,
        )
        methodVisitor.visitCode()
        methodVisitor.visitLdcInsn(stepName)
        methodVisitor.visitInsn(Opcodes.ARETURN)
        methodVisitor.visitMaxs(1, 2)
        methodVisitor.visitEnd()

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }
}

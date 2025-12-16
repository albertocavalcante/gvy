package com.github.albertocavalcante.groovylsp.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for JavaSourceInspector.
 *
 * All tests use a temporary directory to ensure isolation from the user's
 * actual filesystem. This follows Bazel-style sandboxing principles.
 */
class JavaSourceInspectorTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var inspector: JavaSourceInspector

    @BeforeEach
    fun setUp() {
        inspector = JavaSourceInspector()
    }

    @Nested
    inner class InspectClassTest {

        @Test
        fun `inspects simple Java class and finds line number`() {
            // Create a simple Java file
            val javaFile = tempDir.resolve("com/example/Foo.java")
            Files.createDirectories(javaFile.parent)
            Files.writeString(
                javaFile,
                """
                package com.example;
                
                /**
                 * A simple example class.
                 */
                public class Foo {
                    public void bar() {}
                }
                """.trimIndent(),
            )

            val result = inspector.inspectClass(javaFile, "com.example.Foo")

            assertNotNull(result) {
                "Should find the class Foo in the source file"
            }
            assertTrue(result!!.lineNumber > 0) {
                "Line number should be positive, got: ${result.lineNumber}"
            }
        }

        @Test
        fun `inspects Java class with Javadoc and extracts documentation`() {
            val javaFile = tempDir.resolve("com/example/Documented.java")
            Files.createDirectories(javaFile.parent)
            Files.writeString(
                javaFile,
                """
                package com.example;
                
                /**
                 * This is a documented class.
                 *
                 * It has a longer description that spans
                 * multiple lines.
                 */
                public class Documented {
                }
                """.trimIndent(),
            )

            val result = inspector.inspectClass(javaFile, "com.example.Documented")

            assertNotNull(result) {
                "Should find the class Documented"
            }
            assertNotNull(result!!.documentation) {
                "Should have extracted documentation"
            }
            assertTrue(
                result.documentation.summary.isNotBlank() ||
                    result.documentation.description.isNotBlank(),
            ) {
                "Documentation should have content. Got: ${result.documentation}"
            }
        }

        @Test
        fun `returns null for non-existent file`() {
            val result = inspector.inspectClass(tempDir.resolve("nonexistent.java"), "Foo")
            org.junit.jupiter.api.Assertions.assertNull(result)
        }

        @Test
        fun `handles JDK-style Date class`() {
            // Simulate a JDK Date.java file structure
            val javaFile = tempDir.resolve("java/util/Date.java")
            Files.createDirectories(javaFile.parent)
            Files.writeString(
                javaFile,
                """
                package java.util;
                
                /**
                 * The class {@code Date} represents a specific instant
                 * in time, with millisecond precision.
                 */
                public class Date implements java.io.Serializable, Cloneable, Comparable<Date> {
                    public Date() {}
                }
                """.trimIndent(),
            )

            val result = inspector.inspectClass(javaFile, "java.util.Date")

            assertNotNull(result) {
                "Should find java.util.Date class"
            }
            assertTrue(result!!.lineNumber > 0) {
                "Line number should be positive"
            }
            assertNotNull(result.documentation) {
                "Should have documentation"
            }
        }
    }

    @Nested
    inner class RealWorldJavaSourceTest {

        /**
         * Test with a realistic JDK-style Date.java source file.
         * We generate a synthetic file that mimics the actual JDK Date class structure
         * with proper Javadoc, making this test fully hermetic and deterministic.
         */
        @Test
        fun `parses realistic JDK Date class with full Javadoc`() {
            val javaFile = tempDir.resolve("java/util/Date.java")
            Files.createDirectories(javaFile.parent)
            Files.writeString(
                javaFile,
                """
                /*
                 * Test fixture for JavaSourceInspector - mimics JDK Date class structure.
                 */
                package java.util;

                import java.io.IOException;
                import java.io.ObjectOutputStream;
                import java.io.ObjectInputStream;
                import java.time.Instant;

                /**
                 * The class {@code Date} represents a specific instant
                 * in time, with millisecond precision.
                 * <p>
                 * Prior to JDK&nbsp;1.1, the class {@code Date} had two additional
                 * functions.  It allowed the interpretation of dates as year, month, day, hour,
                 * minute, and second values.  It also allowed the formatting and parsing
                 * of date strings.  Unfortunately, the API for these functions was not
                 * amenable to internationalization.
                 *
                 * @author  Test Author A
                 * @author  Test Author B
                 * @author  Test Author C
                 * @see     java.text.DateFormat
                 * @see     java.util.Calendar
                 * @see     java.util.TimeZone
                 * @since   1.0
                 */
                public class Date implements java.io.Serializable, Cloneable, Comparable<Date> {
                    
                    private transient long fastTime;
                    
                    /**
                     * Allocates a {@code Date} object and initializes it so that
                     * it represents the time at which it was allocated, measured to the
                     * nearest millisecond.
                     *
                     * @see     java.lang.System#currentTimeMillis()
                     */
                    public Date() {
                        this(System.currentTimeMillis());
                    }
                    
                    /**
                     * Allocates a {@code Date} object and initializes it to
                     * represent the specified number of milliseconds since the
                     * standard base time known as "the epoch", namely January 1,
                     * 1970, 00:00:00 GMT.
                     *
                     * @param   date   the milliseconds since January 1, 1970, 00:00:00 GMT.
                     * @see     java.lang.System#currentTimeMillis()
                     */
                    public Date(long date) {
                        fastTime = date;
                    }
                    
                    /**
                     * Returns the number of milliseconds since January 1, 1970, 00:00:00 GMT
                     * represented by this {@code Date} object.
                     *
                     * @return  the number of milliseconds since January 1, 1970, 00:00:00 GMT
                     *          represented by this date.
                     */
                    public long getTime() {
                        return fastTime;
                    }
                    
                    /**
                     * Compares two Dates for ordering.
                     *
                     * @param   anotherDate   the {@code Date} to be compared.
                     * @return  the value {@code 0} if the argument Date is equal to
                     *          this Date; a value less than {@code 0} if this Date
                     *          is before the Date argument; and a value greater than
                     *          {@code 0} if this Date is after the Date argument.
                     * @throws  NullPointerException if {@code anotherDate} is null.
                     * @since   1.2
                     */
                    public int compareTo(Date anotherDate) {
                        long thisTime = getTime();
                        long anotherTime = anotherDate.getTime();
                        return (thisTime < anotherTime ? -1 : (thisTime == anotherTime ? 0 : 1));
                    }
                }
                """.trimIndent(),
            )

            val result = inspector.inspectClass(javaFile, "java.util.Date")

            assertNotNull(result) { "Should find java.util.Date class" }

            // Verify line number points to class declaration
            assertTrue(result!!.lineNumber > 0) {
                "Line number should be positive, got: ${result.lineNumber}"
            }

            // Verify documentation was extracted
            val doc = result.documentation
            assertNotNull(doc) { "Should have documentation" }

            // Verify summary extraction
            assertTrue(doc.summary.contains("Date") || doc.summary.contains("instant")) {
                "Summary should mention Date or instant. Got: ${doc.summary}"
            }

            // Verify @author tags
            assertTrue(doc.author.contains("Test Author A")) {
                "Should have Test Author A as author. Got: ${doc.author}"
            }

            // Verify @since tag
            assertEquals("1.0", doc.since) {
                "Should have @since 1.0. Got: ${doc.since}"
            }

            // Verify @see tags
            assertTrue(doc.see.any { it.contains("DateFormat") || it.contains("Calendar") }) {
                "Should have @see references. Got: ${doc.see}"
            }

            println("✓ Line number: ${result.lineNumber}")
            println("✓ Summary: ${doc.summary}")
            println("✓ Author: ${doc.author}")
            println("✓ Since: ${doc.since}")
            println("✓ See: ${doc.see}")
        }

        /**
         * Test with a realistic SimpleDateFormat-style class with complex Javadoc.
         */
        @Test
        fun `parses realistic SimpleDateFormat class with pattern documentation`() {
            val javaFile = tempDir.resolve("java/text/SimpleDateFormat.java")
            Files.createDirectories(javaFile.parent)
            Files.writeString(
                javaFile,
                """
                package java.text;

                import java.util.Date;
                import java.util.Calendar;
                import java.util.Locale;

                /**
                 * {@code SimpleDateFormat} is a concrete class for formatting and
                 * parsing dates in a locale-sensitive manner. It allows for formatting
                 * (date &rarr; text), parsing (text &rarr; date), and normalization.
                 *
                 * <p>
                 * {@code SimpleDateFormat} allows you to start by choosing
                 * any user-defined patterns for date-time formatting. However, you
                 * are encouraged to create a date-time formatter with either
                 * {@code getTimeInstance}, {@code getDateInstance}, or
                 * {@code getDateTimeInstance} in {@code DateFormat}.
                 *
                 * <h2>Date and Time Patterns</h2>
                 * <p>
                 * Date and time formats are specified by <em>date and time pattern</em>
                 * strings. The following pattern letters are defined:
                 * <blockquote>
                 * <table>
                 * <tr><th>Letter</th><th>Description</th></tr>
                 * <tr><td>{@code y}</td><td>Year</td></tr>
                 * <tr><td>{@code M}</td><td>Month in year</td></tr>
                 * <tr><td>{@code d}</td><td>Day in month</td></tr>
                 * </table>
                 * </blockquote>
                 *
                 * @author Test Author D
                 * @author Test Author E
                 * @see java.util.Calendar
                 * @see java.util.TimeZone
                 * @see DateFormat
                 * @since 1.1
                 */
                public class SimpleDateFormat extends DateFormat {
                    
                    private String pattern;
                    
                    /**
                     * Constructs a {@code SimpleDateFormat} using the given pattern and
                     * the default date format symbols for the default locale.
                     *
                     * @param pattern the pattern describing the date and time format
                     * @throws NullPointerException if the given pattern is null
                     * @throws IllegalArgumentException if the given pattern is invalid
                     */
                    public SimpleDateFormat(String pattern) {
                        this.pattern = pattern;
                    }
                    
                    /**
                     * Formats the given {@code Date} into a date/time string and appends
                     * the result to the given {@code StringBuffer}.
                     *
                     * @param date the date-time value to be formatted into a date-time string.
                     * @param toAppendTo where the new date-time text is to be appended.
                     * @param pos keeps track on the position of the field within the returned string.
                     * @return the formatted date-time string.
                     */
                    public StringBuffer format(Date date, StringBuffer toAppendTo, FieldPosition pos) {
                        return toAppendTo.append(pattern);
                    }
                }
                """.trimIndent(),
            )

            val result = inspector.inspectClass(javaFile, "java.text.SimpleDateFormat")

            assertNotNull(result) { "Should find SimpleDateFormat class" }
            assertTrue(result!!.lineNumber > 0) { "Line number should be positive" }

            val doc = result.documentation
            assertTrue(doc.summary.contains("SimpleDateFormat") || doc.summary.contains("formatting")) {
                "Summary should describe SimpleDateFormat. Got: ${doc.summary}"
            }
            assertTrue(doc.author.contains("Test Author D")) {
                "Should have Test Author D as author. Got: ${doc.author}"
            }
            assertEquals("1.1", doc.since) { "Should have @since 1.1" }

            println("✓ SimpleDateFormat parsed successfully")
            println("✓ Summary: ${doc.summary}")
        }

        /**
         * Test parsing a class with @throws/@exception tags.
         */
        @Test
        fun `parses class methods with throws documentation`() {
            val javaFile = tempDir.resolve("com/example/Parser.java")
            Files.createDirectories(javaFile.parent)
            Files.writeString(
                javaFile,
                """
                package com.example;

                import java.io.IOException;

                /**
                 * A parser for processing input data.
                 *
                 * @author Test Author
                 * @since 2.0
                 */
                public class Parser {
                    
                    /**
                     * Parses the input string.
                     *
                     * @param input the input to parse
                     * @return the parsed result
                     * @throws IllegalArgumentException if input is null or empty
                     * @throws IOException if an I/O error occurs
                     */
                    public String parse(String input) throws IOException {
                        if (input == null || input.isEmpty()) {
                            throw new IllegalArgumentException("Input cannot be null or empty");
                        }
                        return input.trim();
                    }
                }
                """.trimIndent(),
            )

            val result = inspector.inspectClass(javaFile, "com.example.Parser")

            assertNotNull(result) { "Should find Parser class" }
            assertEquals("2.0", result!!.documentation.since)
            assertTrue(result.documentation.author.contains("Test Author"))

            println("✓ Parser with throws tags parsed successfully")
        }

        /**
         * Test parsing deprecated class.
         */
        @Test
        fun `parses deprecated class documentation`() {
            val javaFile = tempDir.resolve("com/example/OldApi.java")
            Files.createDirectories(javaFile.parent)
            Files.writeString(
                javaFile,
                """
                package com.example;

                /**
                 * This class provides legacy functionality.
                 *
                 * @deprecated As of version 2.0, replaced by {@link NewApi}.
                 * @see NewApi
                 * @since 1.0
                 */
                @Deprecated
                public class OldApi {
                    public void doSomething() {}
                }
                """.trimIndent(),
            )

            val result = inspector.inspectClass(javaFile, "com.example.OldApi")

            assertNotNull(result) { "Should find OldApi class" }
            val doc = result!!.documentation
            assertTrue(doc.deprecated.contains("2.0") || doc.deprecated.contains("NewApi")) {
                "Should have deprecation notice. Got: ${doc.deprecated}"
            }

            println("✓ Deprecated class parsed successfully")
            println("✓ Deprecated: ${doc.deprecated}")
        }
    }
}

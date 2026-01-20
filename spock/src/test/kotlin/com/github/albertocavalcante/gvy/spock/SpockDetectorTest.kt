package com.github.albertocavalcante.gvy.spock

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpockDetectorTest {

    @Test
    fun `detects spock spec by filename`() {
        val uri = URI.create("file:///src/test/groovy/com/example/FooSpec.groovy")
        val content =
            """
            import spock.lang.Specification

            class FooSpec extends Specification {}
            """.trimIndent()

        assertTrue(SpockDetector.isLikelySpockSpec(uri, content))
    }

    @Test
    fun `detects spock spec by content markers`() {
        val uri = URI.create("file:///src/test/groovy/com/example/FooTest.groovy")
        val content =
            """
            import spock.lang.Specification

            class FooTest extends Specification {
            }
            """.trimIndent()

        assertTrue(SpockDetector.isLikelySpockSpec(uri, content))
    }

    @Test
    fun `does not detect spock when no markers present`() {
        val uri = URI.create("file:///src/main/groovy/com/example/Foo.groovy")
        val content =
            """
            class Foo {
                def bar() {}
            }
            """.trimIndent()

        assertFalse(SpockDetector.isLikelySpockSpec(uri, content))
    }

    @Test
    fun `detects spock spec by wildcard import`() {
        val uri = URI.create("file:///src/test/groovy/com/example/FooTest.groovy")
        val content =
            """
            import spock.lang.*

            class FooTest extends Specification {
            }
            """.trimIndent()

        assertTrue(SpockDetector.isLikelySpockSpec(uri, content))
    }

    @Test
    fun `detects spock spec by fully qualified superclass`() {
        val uri = URI.create("file:///src/test/groovy/com/example/FooTest.groovy")
        val content = "class FooTest extends spock.lang.Specification {}"

        assertTrue(SpockDetector.isLikelySpockSpec(uri, content))
    }

    @Test
    fun `does not detect spock when Specification is from another package`() {
        val uri = URI.create("file:///src/test/groovy/com/example/FooTest.groovy")
        val content =
            """
            import com.example.Specification

            class FooTest extends Specification {
            }
            """.trimIndent()

        assertFalse(SpockDetector.isLikelySpockSpec(uri, content))
    }

    @Test
    fun `detects spock spec by spock star import`() {
        val uri = URI.create("file:///src/test/groovy/com/example/FooTest.groovy")
        val content =
            """
            import spock.*

            class FooTest extends spock.lang.Specification {
            }
            """.trimIndent()

        assertTrue(SpockDetector.isLikelySpockSpec(uri, content))
    }

    @Test
    fun `detects spock spec with different groovy extension casing`() {
        val uri = URI.create("file:///src/test/groovy/com/example/FooSpec.Groovy")
        val content =
            """
            import spock.lang.Specification

            class FooSpec extends Specification {}
            """.trimIndent()

        assertTrue(SpockDetector.isLikelySpockSpec(uri, content))
    }

    @Test
    fun `does not detect spock when markers appear only in a comment`() {
        val uri = URI.create("file:///src/test/groovy/com/example/Foo.groovy")
        val content =
            """
            // import spock.lang.Specification
            class Foo {}
            """.trimIndent()

        assertFalse(SpockDetector.isLikelySpockSpec(uri, content))
    }

    @Test
    fun `does not detect spock when markers appear only in a string literal`() {
        val uri = URI.create("file:///src/test/groovy/com/example/Foo.groovy")
        val content =
            """
            class Foo {
                def x = "import spock.lang.Specification"
            }
            """.trimIndent()

        assertFalse(SpockDetector.isLikelySpockSpec(uri, content))
    }

    @Test
    fun `detects spock spec when superclass is unresolved`() {
        // Simulate a class where 'Specification' is not on the classpath
        // In this case, Groovy resolves superClass to Object, but keeps the
        // unresolved name in unresolvedSuperClass
        val classNode = ClassNode("com.example.FooSpec", 0, ClassHelper.OBJECT_TYPE)
        val unresolvedSuper = ClassNode("spock.lang.Specification", 0, ClassHelper.OBJECT_TYPE)
        classNode.unresolvedSuperClass = unresolvedSuper

        assertTrue(SpockDetector.isSpockSpec(classNode))
    }

    @Test
    fun `detects spock spec by block labels when extending custom base class`() {
        val uri = URI.create("file:///src/test/groovy/com/example/MySpec.groovy")
        val content =
            """
            class MySpec extends BaseSpec {
                def "should work"() {
                    given:
                    def x = 1

                    when:
                    def result = x + 1

                    then:
                    result == 2
                }
            }
            """.trimIndent()
        assertTrue(SpockDetector.isLikelySpockSpec(uri, content))
    }

    @Test
    fun `detects spock spec with expect block`() {
        val uri = URI.create("file:///src/test/groovy/com/example/MySpec.groovy")
        val content =
            """
            class MySpec extends BaseTest {
                def "test with expect"() {
                    expect:
                    1 + 1 == 2
                }
            }
            """.trimIndent()
        assertTrue(SpockDetector.isLikelySpockSpec(uri, content))
    }

    @Test
    fun `detects spock spec with where block`() {
        val uri = URI.create("file:///src/test/groovy/com/example/MySpec.groovy")
        val content =
            """
            class MySpec extends BaseSpec {
                def "parameterized test"() {
                    expect:
                    a + b == c

                    where:
                    a | b | c
                    1 | 2 | 3
                }
            }
            """.trimIndent()
        assertTrue(SpockDetector.isLikelySpockSpec(uri, content))
    }

    @Test
    fun `detects spock spec with cleanup block`() {
        val uri = URI.create("file:///src/test/groovy/com/example/MySpec.groovy")
        val content =
            """
            class MySpec extends BaseTest {
                def "test with cleanup"() {
                    given:
                    def resource = openResource()

                    when:
                    resource.use()

                    then:
                    resource.wasUsed()

                    cleanup:
                    resource.close()
                }
            }
            """.trimIndent()
        assertTrue(SpockDetector.isLikelySpockSpec(uri, content))
    }

    @Test
    fun `does not detect spock when block label is in string literal`() {
        val uri = URI.create("file:///src/main/groovy/com/example/MyClass.groovy")
        val content =
            """
            class MyClass {
                def test() {
                    println "given: this is not spock"
                    def text = '''
                    when: this is also not a spock block
                    '''
                }
            }
            """.trimIndent()
        assertFalse(SpockDetector.isLikelySpockSpec(uri, content))
    }

    @Test
    fun `does not detect spock when block label is in comment`() {
        val uri = URI.create("file:///src/main/groovy/com/example/MyClass.groovy")
        val content =
            """
            class MyClass {
                def test() {
                    // given: this is not spock
                    /*
                    when: this is also not spock
                    then: still not spock
                    */
                    println "hello"
                }
            }
            """.trimIndent()
        assertFalse(SpockDetector.isLikelySpockSpec(uri, content))
    }

    @Test
    fun `does not detect spock when only partial block label match`() {
        val uri = URI.create("file:///src/main/groovy/com/example/MyClass.groovy")
        val content =
            """
            class MyClass {
                def test() {
                    def mapGiven = [key: "value"]
                    def whenValue = "test"
                }
            }
            """.trimIndent()
        assertFalse(SpockDetector.isLikelySpockSpec(uri, content))
    }
}

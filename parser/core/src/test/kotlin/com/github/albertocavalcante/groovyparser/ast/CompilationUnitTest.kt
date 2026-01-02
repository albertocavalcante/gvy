package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CompilationUnitTest {

    private val parser = GroovyParser()

    @Test
    fun `empty compilation unit has no package`() {
        val unit = CompilationUnit()

        assertThat(unit.packageDeclaration).isEmpty()
    }

    @Test
    fun `empty compilation unit has no imports`() {
        val unit = CompilationUnit()

        assertThat(unit.imports).isEmpty()
    }

    @Test
    fun `empty compilation unit has no types`() {
        val unit = CompilationUnit()

        assertThat(unit.types).isEmpty()
    }

    @Test
    fun `can add package declaration`() {
        val unit = CompilationUnit()
        val pkg = PackageDeclaration("com.example")

        unit.setPackageDeclaration(pkg)

        assertThat(unit.packageDeclaration).isPresent
        assertThat(unit.packageDeclaration.get().name).isEqualTo("com.example")
    }

    @Test
    fun `can add imports`() {
        val unit = CompilationUnit()

        unit.addImport(ImportDeclaration("java.util.List"))
        unit.addImport(ImportDeclaration("java.util.Map"))

        assertThat(unit.imports).hasSize(2)
    }

    @Test
    fun `can add type declarations`() {
        val unit = CompilationUnit()

        unit.addType(ClassDeclaration("Foo"))
        unit.addType(ClassDeclaration("Bar"))

        assertThat(unit.types).hasSize(2)
        assertThat(unit.types.map { it.name }).containsExactly("Foo", "Bar")
    }

    @Test
    fun `parsed compilation unit has correct structure`() {
        val result = parser.parse(
            """
            package com.example
            
            import java.util.List
            
            class Foo {
            }
            
            class Bar {
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val unit = result.result.get()

        assertThat(unit.packageDeclaration).isPresent
        assertThat(unit.packageDeclaration.get().name).isEqualTo("com.example")
        assertThat(unit.imports).hasSize(1)
        assertThat(unit.types).hasSize(2)
    }

    @Test
    fun `compilation unit toString is readable`() {
        val unit = CompilationUnit()
        unit.setPackageDeclaration(PackageDeclaration("com.example"))
        unit.addImport(ImportDeclaration("java.util.List"))
        unit.addType(ClassDeclaration("Foo"))

        val str = unit.toString()

        assertThat(str).contains("CompilationUnit")
        assertThat(str).contains("com.example")
        assertThat(str).contains("1 import")
        assertThat(str).contains("1 type")
    }
}

package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.Position
import com.github.albertocavalcante.groovyparser.Range
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NodeTest {

    @Test
    fun `node has optional range`() {
        val node = CompilationUnit()

        assertThat(node.getRange()).isEmpty()

        node.range = Range(Position(1, 1), Position(10, 1))
        assertThat(node.getRange()).isPresent
        assertThat(node.getRange().get().begin.line).isEqualTo(1)
    }

    @Test
    fun `node has optional parent`() {
        val parent = CompilationUnit()
        val child = PackageDeclaration("com.example")

        assertThat(child.getParentNode()).isEmpty()

        parent.setPackageDeclaration(child)
        assertThat(child.getParentNode()).isPresent
        assertThat(child.getParentNode().get()).isSameAs(parent)
    }

    @Test
    fun `node returns child nodes`() {
        val unit = CompilationUnit()
        val pkg = PackageDeclaration("com.example")
        val import1 = ImportDeclaration("java.util.List")
        val import2 = ImportDeclaration("java.util.Map")

        unit.setPackageDeclaration(pkg)
        unit.addImport(import1)
        unit.addImport(import2)

        val children = unit.getChildNodes()
        assertThat(children).hasSize(3)
        assertThat(children).contains(pkg, import1, import2)
    }

    @Test
    fun `child nodes have parent set correctly`() {
        val unit = CompilationUnit()
        val pkg = PackageDeclaration("com.example")

        unit.setPackageDeclaration(pkg)

        assertThat(pkg.parentNode).isSameAs(unit)
    }
}

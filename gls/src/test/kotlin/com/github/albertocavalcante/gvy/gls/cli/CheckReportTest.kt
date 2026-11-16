// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.cli

import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CheckReportTest {
    @Test
    fun `absolute path inside the workspace becomes a relative uri`(@TempDir ws: File) {
        val file = File(ws, "src/A.groovy")
        assertThat(sarifArtifactUri(file, ws)).isEqualTo("src/A.groovy")
    }

    @Test
    fun `relative workspace with absolute file is relativized`() {
        val ws = File(".")
        val file = File(ws.absoluteFile, "src/A.groovy")
        assertThat(sarifArtifactUri(file, ws)).isEqualTo("src/A.groovy")
    }

    @Test
    fun `spaces and non ascii are percent encoded`(@TempDir ws: File) {
        val uri = sarifArtifactUri(File(ws, "My File/déjà.groovy"), ws)
        assertThat(uri).isEqualTo("My%20File/d%C3%A9j%C3%A0.groovy")
    }

    @Test
    fun `file outside the workspace gets a file uri`(@TempDir ws: File, @TempDir other: File) {
        val file = File(other, "B.groovy")
        assertThat(sarifArtifactUri(file, ws)).isEqualTo(file.absoluteFile.normalize().toURI().toString())
    }

    @Test
    fun `plain text report has labels and no ansi codes`() {
        val file = File("a.groovy")
        val diagnostic = Diagnostic(Range(Position(0, 1), Position(0, 2)), "boom", DiagnosticSeverity.Error, "x")
        val report = plainTextReport(mapOf(file to listOf(diagnostic), File("b.groovy") to emptyList()))
        assertThat(report).isEqualTo("a.groovy:1:2: [ERROR] boom\nOK: b.groovy\n")
    }

    @Test
    fun `output file receives the text report and null output writes nothing`(@TempDir dir: File) {
        val diagnostic = Diagnostic(Range(Position(2, 0), Position(2, 1)), "warn", DiagnosticSeverity.Warning, "x")
        val diagnostics = mapOf(File("a.groovy") to listOf(diagnostic))
        val out = File(dir, "report.txt")

        assertThat(writeTextReportTo(out, diagnostics)).isTrue()
        assertThat(out.readText()).isEqualTo("a.groovy:3:1: [WARNING] warn\n")
        assertThat(writeTextReportTo(null, diagnostics)).isFalse()
    }

    @Test
    fun `relative file without workspace stays relative and colon segment is guarded`() {
        assertThat(sarifArtifactUri(File("src/A.groovy"), null)).isEqualTo("src/A.groovy")
        assertThat(sarifArtifactUri(File("a:b/x.groovy"), null)).isEqualTo("./a:b/x.groovy")
        assertThat(sarifArtifactUri(File("a#b?.groovy"), null)).isEqualTo("a%23b%3F.groovy")
    }
}

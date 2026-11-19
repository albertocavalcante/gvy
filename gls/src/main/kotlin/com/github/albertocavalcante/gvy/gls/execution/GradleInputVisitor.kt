// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.execution

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal fun fingerprintInputs(root: Path?, contexts: List<FileExecutionContext>, runtime: List<String>): String? =
    try {
        val files = contexts.flatMap { it.inputFiles }.toMutableSet()
        files.addAll(runtime.map { Path.of(it) })
        if (root != null) Files.walkFileTree(root, GradleInputVisitor(root, files))
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in files.map { it.toAbsolutePath().normalize() }.sortedBy(Path::toString)) {
            addFileToDigest(digest, file)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: java.io.IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

private class GradleInputVisitor(private val root: Path, private val files: MutableSet<Path>) :
    SimpleFileVisitor<Path>() {
    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        val excluded = dir.fileName.toString() in setOf(".git", ".gradle", "build", "node_modules")
        return if (dir != root && excluded) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
    }

    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        val name = file.fileName.toString()
        val script = listOf(".gradle", ".gradle.kts").any(name::endsWith)
        val config = name in setOf("gradle.properties", "libs.versions.toml")
        if (script || config) files.add(file)
        return FileVisitResult.CONTINUE
    }
}

private fun addFileToDigest(digest: MessageDigest, file: Path) {
    val pathBytes = file.toString().toByteArray(Charsets.UTF_8)
    val before = Files.readAttributes(file, BasicFileAttributes::class.java)
    digest.update("${pathBytes.size}:".toByteArray(Charsets.UTF_8))
    digest.update(pathBytes)
    digest.update("${before.size()}:".toByteArray(Charsets.UTF_8))
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val after = Files.readAttributes(file, BasicFileAttributes::class.java)
    if (before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime()) {
        throw java.io.IOException("Project input changed while it was being read")
    }
}

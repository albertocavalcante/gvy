// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.Usage
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** These resource names and limits are consumed by RuntimeGroovySdk and BundledSourceCatalog. */
object GroovySdkResources {
    const val prefix = "META-INF/gvy/sources/"
    const val manifest = "groovy.properties"
    const val classes = "groovy-class-digests.properties"
    const val sources = "groovy-sources.jar"
    const val archiveLimit = 64L * 1024 * 1024
    const val containerLimit = 512L * 1024 * 1024
    const val metadataLimit = 2 * 1024 * 1024
    const val classLimit = 2L * 1024 * 1024

    fun isCore(id: ComponentIdentifier): Boolean = id is ModuleComponentIdentifier &&
        id.module == "groovy" && id.group in setOf("org.apache.groovy", "org.codehaus.groovy")

    fun digest(input: InputStream, maximum: Long, copy: OutputStream? = null): String = input.use {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        var read = it.read(buffer)
        while (read != -1) {
            total += read
            require(total <= maximum) { "Archive data exceeds $maximum bytes" }
            hash.update(buffer, 0, read)
            copy?.write(buffer, 0, read)
            read = it.read(buffer)
        }
        HexFormat.of().formatHex(hash.digest())
    }

    private fun classKey(name: String): Boolean = name.length <= 1024 &&
        name.removeSuffix(".class").split('/').all { part ->
            part.isNotEmpty() && Character.isJavaIdentifierStart(part.codePointAt(0)) &&
                part.codePoints().allMatch { Character.isJavaIdentifierPart(it) && !Character.isISOControl(it) }
        }

    fun classIndex(binary: File): Map<String, String> = ZipFile(binary).use { jar ->
        val entries = sortedMapOf<String, String>()
        var expanded = 0L
        for (entry in jar.entries().asSequence()) {
            if (entry.isDirectory || !entry.name.endsWith(".class")) continue
            require(classKey(entry.name) && entry.name !in entries) { "Invalid or duplicate SDK class: ${entry.name}" }
            require(entries.size < 20_000 && entry.size in 0..classLimit && entry.size <= archiveLimit - expanded) {
                "Groovy SDK class archive exceeds consumer bounds at ${entry.name}"
            }
            expanded += entry.size
            entries[entry.name] = digest(jar.getInputStream(entry), entry.size)
        }
        require("groovy/lang/GroovyObject.class" in entries) { "Missing Groovy core anchor" }
        entries
    }

    private fun properties(values: Map<String, String>): ByteArray {
        val bytes = values.toSortedMap().entries.joinToString("", transform = { (key, value) -> "$key=$value\n" })
            .toByteArray(Charsets.UTF_8)
        require(bytes.size <= metadataLimit) { "SDK metadata exceeds consumer limit" }
        return bytes
    }

    fun write(binary: File, source: File, group: String, version: String, directory: File) {
        require(group in setOf("org.apache.groovy", "org.codehaus.groovy") && version.matches(Regex("[A-Za-z0-9_.+-]+")))
        require(binary.length() in 1..archiveLimit && source.length() in 1..archiveLimit)
        val index = properties(classIndex(binary))
        directory.mkdirs()
        val sourceDigest = directory.resolve(sources).outputStream().buffered().use { output ->
            digest(source.inputStream(), archiveLimit, copy = output)
        }
        val metadata = properties(
            mapOf(
                "format" to "1",
                "group" to group,
                "module" to "groovy",
                "version" to version,
                "binarySha256" to digest(binary.inputStream(), archiveLimit),
                "sourceSha256" to sourceDigest,
                "sourceResource" to prefix + sources,
            ),
        )
        directory.resolve(classes).writeBytes(index)
        directory.resolve(manifest).writeBytes(metadata)
    }
}

@CacheableTask
abstract class GenerateGroovySdkMetadata : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val binary: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sources: RegularFileProperty

    @get:Input
    abstract val componentGroup: Property<String>
    @get:Input
    abstract val componentVersion: Property<String>
    @get:OutputDirectory
    abstract val resources: DirectoryProperty

    @TaskAction
    fun generate() = GroovySdkResources.write(
        binary.get().asFile,
        sources.get().asFile,
        componentGroup.get(),
        componentVersion.get(),
        resources.get().asFile,
    )
}

val runtimeArtifacts = configurations.getByName("runtimeClasspath").incoming
val coreBinary = runtimeArtifacts.artifactView {
    componentFilter(GroovySdkResources::isCore)
}.artifacts.resolvedArtifacts.map { it.single() }
val coreSources = runtimeArtifacts.artifactView {
    withVariantReselection()
    componentFilter(GroovySdkResources::isCore)
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
        attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
}.artifacts.resolvedArtifacts.map { it.single() }
val coreId = coreBinary.map { it.id.componentIdentifier as ModuleComponentIdentifier }
val sourceFile = coreId.zip(coreSources) { expected, artifact ->
    require(artifact.id.componentIdentifier == expected) {
        "Groovy sources must match the selected runtime component $expected"
    }
    artifact.file
}
val generate = tasks.register<GenerateGroovySdkMetadata>("generateGroovySdkMetadata") {
    binary.set(layout.file(coreBinary.map { it.file }))
    sources.set(layout.file(sourceFile))
    componentGroup.set(coreId.map { it.group })
    componentVersion.set(coreId.map { it.version })
    resources.set(layout.buildDirectory.dir("generated/packagedGroovySdk"))
}
val shaded = tasks.named<Jar>("shadowJar") {
    from(generate.flatMap { it.resources }) {
        into(GroovySdkResources.prefix)
        include(GroovySdkResources.manifest, GroovySdkResources.classes, GroovySdkResources.sources)
    }
}

/** Verify the deliverable against its original components, including classes Shadow might have changed or omitted. */
abstract class VerifyGroovySdkMetadata : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val binary: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sources: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val application: RegularFileProperty

    @get:Input
    abstract val componentGroup: Property<String>
    @get:Input
    abstract val componentVersion: Property<String>

    @TaskAction
    fun verify() {
        verifyPackage(
            binary.get().asFile,
            sources.get().asFile,
            componentGroup.get(),
            componentVersion.get(),
            application.get().asFile,
        )
        val scratch = Files.createTempDirectory(temporaryDir.toPath(), "sdk-contract-").toFile()
        try {
            verifyGenerator(scratch)
        } finally {
            check(scratch.deleteRecursively())
        }
    }

    private fun verifyPackage(original: File, source: File, group: String, version: String, application: File) {
        check(Files.size(application.toPath()) in 1..GroovySdkResources.containerLimit) {
            "Packaged SDK container exceeds consumer bounds"
        }
        JarFile(application, false).use { shaded ->
            val resources = ownedResources(shaded)
            val manifest = readProperties(shaded, resources.getValue(GroovySdkResources.manifest))
            check(manifest.getProperty("format") == "1")
            check(manifest.getProperty("module") == "groovy")
            check(manifest.getProperty("group") == group)
            check(manifest.getProperty("version") == version)
            val binarySha = GroovySdkResources.digest(original.inputStream(), GroovySdkResources.archiveLimit)
            check(manifest.getProperty("binarySha256") == binarySha)
            val sourceSha = GroovySdkResources.digest(source.inputStream(), GroovySdkResources.archiveLimit)
            check(manifest.getProperty("sourceSha256") == sourceSha)
            val sourceEntry = resources.getValue(GroovySdkResources.sources)
            check(manifest.getProperty("sourceResource") == sourceEntry.name)
            val embeddedSource = shaded.getInputStream(sourceEntry)
            check(GroovySdkResources.digest(embeddedSource, GroovySdkResources.archiveLimit) == sourceSha)

            val index = readProperties(shaded, resources.getValue(GroovySdkResources.classes))
            ZipFile(original).use { core -> verifyClasses(core, shaded, index) }
        }
    }

    private fun ownedResources(jar: JarFile): Map<String, ZipEntry> {
        val names = setOf(GroovySdkResources.manifest, GroovySdkResources.classes, GroovySdkResources.sources)
        val paths = names.associateBy { GroovySdkResources.prefix + it }
        val resources = mutableMapOf<String, ZipEntry>()
        for (entry in jar.entries().asSequence()) {
            val name = paths[entry.name] ?: continue
            check(resources.put(name, entry) == null) { "Duplicate packaged SDK resource: ${entry.name}" }
        }
        check(resources.keys == names) { "Missing packaged SDK resource" }
        return resources
    }

    private fun readProperties(jar: ZipFile, entry: ZipEntry): Properties {
        val bytes = jar.getInputStream(entry).use { it.readNBytes(GroovySdkResources.metadataLimit + 1) }
        check(bytes.size <= GroovySdkResources.metadataLimit) { "Oversized packaged SDK metadata: ${entry.name}" }
        return bytes.inputStream().bufferedReader(Charsets.UTF_8).use { reader -> Properties().apply { load(reader) } }
    }

    private fun verifyClasses(core: ZipFile, shaded: JarFile, index: Properties) {
        val keys = core.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }
            .map { it.name }.toSet()
        check(index.stringPropertyNames() == keys) { "SDK class authority differs from the original Groovy component" }
        val seen = mutableSetOf<String>()
        val multiRelease = shaded.manifest?.mainAttributes?.getValue("Multi-Release").equals("true", ignoreCase = true)
        val versions = if (multiRelease) Regex("META-INF/versions/([1-9][0-9]*)/(.+\\.class)") else null
        for (entry in shaded.entries().asSequence()) {
            val alias = versions?.matchEntire(entry.name)
            if (alias != null && (alias.groupValues[1].toIntOrNull() ?: 0) >= 9) {
                check(alias.groupValues[2] !in keys) { "Versioned class aliases packaged SDK class: ${entry.name}" }
            }
            if (entry.name !in keys) continue
            check(seen.add(entry.name)) { "Duplicate shaded SDK class: ${entry.name}" }
            val originalBytes = core.getInputStream(core.getEntry(entry.name))
            val originalDigest = GroovySdkResources.digest(originalBytes, GroovySdkResources.classLimit)
            val shadedDigest = GroovySdkResources.digest(shaded.getInputStream(entry), GroovySdkResources.classLimit)
            check(originalDigest == index.getProperty(entry.name) && originalDigest == shadedDigest) {
                "Shaded class differs from the indexed Groovy component: ${entry.name}"
            }
        }
        check(seen == keys) { "Shaded application omitted Groovy SDK classes" }
    }

    private fun verifyGenerator(directory: File) {
        val anchor = "groovy/lang/GroovyObject.class"
        val other = "example/Leaf.class"
        val first = directory.resolve("first.jar")
        val second = directory.resolve("second.jar")
        val source = directory.resolve("sources.jar")
        val index = directory.resolve("result")
        writeZip(source, listOf("groovy/lang/GroovyObject.java"))
        writeZip(first, listOf(anchor, other))
        writeZip(second, listOf(other, anchor))
        fun generate(binary: File) = GroovySdkResources.write(binary, source, "org.apache.groovy", "4.0.33", index)
        generate(first)
        val firstClassIndex = index.resolve(GroovySdkResources.classes).readText()
        val firstManifest = index.resolve(GroovySdkResources.manifest).readText()
        fun unchangedIndexAndSources() {
            check(firstClassIndex == index.resolve(GroovySdkResources.classes).readText())
            check(source.readBytes().contentEquals(index.resolve(GroovySdkResources.sources).readBytes()))
        }
        generate(first)
        unchangedIndexAndSources()
        check(firstManifest == index.resolve(GroovySdkResources.manifest).readText())
        generate(second)
        unchangedIndexAndSources()
        check(firstManifest != index.resolve(GroovySdkResources.manifest).readText()) {
            "Whole ZIP identity must follow its actual bytes"
        }

        for (name in listOf("../Escape.class", "/Absolute.class", "a\\b.class", "a//B.class")) {
            writeZip(second, listOf(anchor, name))
            check(runCatching { generate(second) }.exceptionOrNull() is IllegalArgumentException) { "Accepted $name" }
        }
        // ZipOutputStream refuses duplicate names. Give two entries the same equal-length name in both ZIP headers.
        val peer = "example/Peer.class"
        writeZip(second, listOf(anchor, other, peer), byteArrayOf(1))
        val headers = second.readBytes().toString(Charsets.ISO_8859_1)
        second.writeBytes(headers.replace(peer, other).toByteArray(Charsets.ISO_8859_1))
        check(ZipFile(second).use { it.entries().asSequence().count { entry -> entry.name == other } } == 2)
        check(runCatching { generate(second) }.exceptionOrNull() is IllegalArgumentException) { "Accepted duplicate class" }

        generate(first)
        verifyRejectedPackages(directory, first, source, index, listOf(anchor, other))

        writeZip(second, listOf(anchor, other), ByteArray(GroovySdkResources.classLimit.toInt() + 1))
        check(runCatching { generate(second) }.exceptionOrNull() is IllegalArgumentException) { "Accepted oversized class" }
    }

    private fun verifyRejectedPackages(directory: File, core: File, source: File, generated: File, classes: List<String>) {
        val target = directory.resolve("application.jar")
        val base = classes.associateWith { it.toByteArray(Charsets.UTF_8) }.toMutableMap()
        base["META-INF/MANIFEST.MF"] = "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".toByteArray()
        val resources = listOf(GroovySdkResources.manifest, GroovySdkResources.classes, GroovySdkResources.sources)
        for (name in resources) base[GroovySdkResources.prefix + name] = generated.resolve(name).readBytes()
        fun assemble(extra: Map<String, ByteArray> = emptyMap()) {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                for ((name, data) in base + extra) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }
        fun verify() = verifyPackage(core, source, "org.apache.groovy", "4.0.33", target)
        fun rejected(message: String) {
            val failure = runCatching { verify() }.exceptionOrNull()
            check(failure is IllegalStateException && failure.message == message) { "Expected '$message', got $failure" }
        }
        assemble()
        verify()
        for (name in resources) {
            val path = GroovySdkResources.prefix + name
            val placeholder = "X" + path.drop(1)
            assemble(mapOf(placeholder to byteArrayOf(1)))
            val bytes = target.readBytes().toString(Charsets.ISO_8859_1)
            target.writeBytes(bytes.replace(placeholder, path).toByteArray(Charsets.ISO_8859_1))
            rejected("Duplicate packaged SDK resource: $path")
        }
        for (name in listOf(GroovySdkResources.manifest, GroovySdkResources.classes)) {
            val path = GroovySdkResources.prefix + name
            assemble(mapOf(path to ByteArray(GroovySdkResources.metadataLimit + 1)))
            rejected("Oversized packaged SDK metadata: $path")
            understateSize(target, path)
            rejected("Oversized packaged SDK metadata: $path")
        }
        // Entry names below META-INF are not version-selected, and unrelated versioned classes are not SDK classes.
        assemble(mapOf(
            "META-INF/versions/17/" + GroovySdkResources.prefix + GroovySdkResources.manifest to byteArrayOf(1),
            "META-INF/versions/17/example/Foreign.class" to byteArrayOf(2),
        ))
        verify()
        val alias = "META-INF/versions/17/${classes.first()}"
        assemble(mapOf(alias to byteArrayOf(1)))
        rejected("Versioned class aliases packaged SDK class: $alias")

        // A sparse, deliberately invalid ZIP proves the size guard runs before opening the container.
        Files.delete(target.toPath())
        val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)
        FileChannel.open(target.toPath(), options).use { channel ->
            channel.position(GroovySdkResources.containerLimit)
            channel.write(ByteBuffer.wrap(byteArrayOf(0)))
        }
        rejected("Packaged SDK container exceeds consumer bounds")
    }

    /** In this controlled fixture the last name is the central directory record, after the local header. */
    private fun understateSize(jar: File, name: String) {
        val bytes = jar.readBytes()
        val header = bytes.toString(Charsets.ISO_8859_1).lastIndexOf(name) - 46
        val records = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(header >= 0 && records.getInt(header) == 0x02014b50)
        check(records.getInt(header + 24) == GroovySdkResources.metadataLimit + 1)
        records.putInt(header + 24, 1)
        jar.writeBytes(bytes)
        ZipFile(jar).use { check(it.getEntry(name).size == 1L) }
    }

    private fun writeZip(file: File, names: List<String>, payload: ByteArray? = null) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(payload ?: name.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}

val verifySdk = tasks.register<VerifyGroovySdkMetadata>("verifyGroovySdkMetadata") {
    group = "verification"
    description = "Verify the packaged Groovy SDK against its original binary and source artifacts."
    binary.set(generate.flatMap { it.binary })
    sources.set(generate.flatMap { it.sources })
    componentGroup.set(coreId.map { it.group })
    componentVersion.set(coreId.map { it.version })
    application.set(shaded.flatMap { it.archiveFile })
}
tasks.named("check") { dependsOn(verifySdk) }
tasks.named("stageEditorServer") { dependsOn(verifySdk) }

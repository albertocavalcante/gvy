package com.github.albertocavalcante.gvy.gls.e2e

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Resolves a relative artifact name without allowing it to escape its configured directory. */
internal fun resolveArtifactPath(directory: Path, name: String, kind: String): Path {
    require(name.isNotBlank()) { "$kind name must not be blank" }
    val base = directory.toAbsolutePath().normalize()
    val file = base.resolve(name).normalize()
    require(!Path.of(name).isAbsolute && file.startsWith(base) && file != base) {
        "$kind '$name' must be a relative path inside the $kind directory"
    }
    require(file.resolveExistingAncestor().startsWith(base.resolveExistingAncestor())) {
        "$kind '$name' resolves outside the $kind directory through a symbolic link"
    }
    return file
}

/** Resolves existing symbolic links while allowing an artifact or its directory to be created later. */
private fun Path.resolveExistingAncestor(): Path {
    var ancestor = this
    val missing = ArrayDeque<String>()
    while (!Files.exists(ancestor, NOFOLLOW_LINKS)) {
        missing.addFirst(ancestor.fileName.toString())
        ancestor = checkNotNull(ancestor.parent) { "No existing ancestor for $this" }
    }
    return missing.fold(ancestor.toRealPath()) { path, name -> path.resolve(name) }
}

/** CI compares committed artifacts; updating them is an explicit local operation. */
internal fun requireLocalArtifactUpdateAllowed(
    environment: Map<String, String?>,
    kind: String,
    markerProperty: String,
    updateProperty: String,
    properties: (String) -> String?,
) {
    check(environment["GITHUB_ACTIONS"].isNullOrEmpty()) {
        "Refusing to update $kind when GITHUB_ACTIONS is set; update locally and commit the files."
    }
    check(environment["CI"].isNullOrEmpty()) {
        "Refusing to update $kind when CI is set; update locally and commit the files."
    }
    check(environment["TEST_TARGET"].isNullOrEmpty() && properties(markerProperty) == null) {
        "Refusing to update $kind under Bazel; run the Gradle e2eTest task with -D$updateProperty=true."
    }
}

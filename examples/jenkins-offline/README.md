<!-- SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Offline Jenkins editing example

Open this directory as a VS Code workspace. It contains two pipeline styles and local metadata inputs; it does not
contact Jenkins or need a controller account. Without controller-specific metadata, completions are limited to gvy's
bundled catalogue. The declarations in `pipeline.gdsl` contribute after you configure its absolute path as shown below.

```text
jenkins-offline/
├── .vscode/settings.json        # Jenkins patterns and plugins.txt path
├── Jenkinsfile                  # Declarative pipeline with a script { } body
├── Jenkinsfile-scripted         # Scripted pipeline, including top-level code outside node { }
├── pipeline.gdsl                # Small illustrative local contribution
├── plugins.txt                  # Plugin IDs; no versions in this offline sample
└── shared-library/
    ├── src/org/example/BuildHelper.groovy
    ├── vars/release.groovy      # Jenkins layout reference; not directory-mapped by current gvy settings
    ├── vars/release.txt
    └── resources/org/example/build.properties
```

`settings.json` lists all patterns used by these files because `groovy.jenkins.filePatterns` replaces the defaults. The
plugin manifest path is workspace-relative. The checked-in manifest uses plugin names without versions so it doesn't ask
for version-specific plugin artifacts. Adding versions improves the input's provenance, but metadata resolution for a
versioned plugin may require network access if the artifact is not cached.

GDSL paths and `groovy.jenkins.gdslExecution.enabled` are machine-scoped, so they are not set in this workspace file. To
use the sample `pipeline.gdsl`, add its **absolute path** in VS Code user settings and set the compatibility switch to
`true`, then restart the language server:

```json
{
  "groovy.jenkins.gdslPaths": ["/absolute/path/to/jenkins-offline/pipeline.gdsl"],
  "groovy.jenkins.gdslExecution.enabled": true
}
```

Despite its retained setting name, gvy parses GDSL with a restricted AST interpreter; it never executes the script.
Unsupported declarations are skipped. For a real controller-specific profile, prepare and verify the response from
`/pipeline-syntax/gdsl` as described in the
[Jenkins user guide](../../docs/user/jenkins.md#prepare-controller-metadata).

## Current shared-library configuration

The `shared-library/` tree shows Jenkins's documented source layout, but current gvy settings do not map a directory to
a library. They accept a compiled JAR and optional sources JAR. To use compiled `src/` classes, prepare those artifacts
with your project's Groovy build, then set the library alias to their absolute paths in VS Code settings:

```json
{
  "groovy.jenkins.sharedLibraries": [
    {
      "name": "team-library",
      "jar": "/absolute/path/to/team-library.jar",
      "sourcesJar": "/absolute/path/to/team-library-sources.jar"
    }
  ]
}
```

Use `@Library('team-library')` in a pipeline to reference that configured alias. Current JAR setup provides the compiled
classes and sources for classpath/navigation use. It does not load `vars/` scripts or their help files from this example
directory as a Jenkins shared library. See the
[Jenkins shared-library layout](https://www.jenkins.io/doc/book/pipeline/shared-libraries/#directory-structure) and the
current [gvy Jenkins support limits](../../docs/user/jenkins.md#plugin-manifest-and-local-libraries).

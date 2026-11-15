<!-- SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Edit Jenkins pipelines

gvy provides Groovy editing features for Jenkins pipeline files. It does not connect to a Jenkins controller, run a
Pipeline, or prove that a particular Jenkins installation accepts it. Step metadata depends on the bundled catalogue and
any metadata you prepare locally.

Jenkins Scripted Pipeline uses Groovy flow control, while Declarative Pipeline has its own structure and allows
executable Groovy inside `script {}`. See the
[Jenkins Pipeline syntax reference](https://www.jenkins.io/doc/book/pipeline/syntax/) and
[Jenkinsfile guide](https://www.jenkins.io/doc/book/pipeline/jenkinsfile/) for the controller-side language rules.

Open a `Jenkinsfile` or a name matching the default Jenkins file patterns. The defaults include `Jenkinsfile`,
`Jenkinsfile-*`, `Jenkinsfile_*`, `Jenkinsfile.*`, `*.jenkinsfile`, `*.Jenkinsfile`, and `vars/*.groovy`. Setting
`groovy.jenkins.filePatterns` replaces the whole list, so include every pattern you need.

The [offline example project](../../examples/jenkins-offline/) contains scripted and declarative pipelines, a saved GDSL
sample, a plugin manifest, and a shared-library source layout. It opens without Jenkins credentials or network access.
The library directory documents Jenkins's layout; current gvy configuration accepts a library JAR and an optional
sources JAR, not a directory mapping. Library `vars/` globals and their companion documentation from an arbitrary mapped
directory are not yet a supported workflow.

## Prepare controller metadata

Jenkins's Pipeline Syntax page is generated from the plugins installed on that controller. From an authorized network,
save the GDSL endpoint output into a local file:

```sh
curl --fail --silent --show-error --location \
  --user 'jenkins-user' \
  'https://jenkins.example.invalid/pipeline-syntax/gdsl' \
  --output pipeline.gdsl
```

With `--user` and no password argument, curl prompts for the password; use a Jenkins API token where your Jenkins
administrator permits it. Do not put a password or token in the URL, command line, settings, or repository. Use HTTPS
and your organization's certificate configuration.

Jenkins may return a login or proxy error page instead of GDSL. Check the response before saving it as metadata:

```sh
file pipeline.gdsl
head -n 5 pipeline.gdsl
```

The file should be readable text containing GDSL calls such as `contributor` and `method`; HTML markup, a login form, or
an error message means authentication or access failed. Do not point gvy at that response. The example's `pipeline.gdsl`
is a small illustrative declaration, not a dump from a real controller.

In VS Code user settings, configure the saved file's absolute path and enable the legacy GDSL metadata switch:

```json
{
  "groovy.jenkins.gdslPaths": ["/absolute/path/to/pipeline.gdsl"],
  "groovy.jenkins.gdslExecution.enabled": true
}
```

These settings are machine-scoped, so a workspace cannot turn them on. The setting name is retained for compatibility:
gvy reads the file with a restricted AST interpreter and does not execute the Groovy script. Unsupported constructs are
skipped. Some versions still describe script execution in the settings UI; the reader uses the restricted interpreter.
After replacing the saved file or changing its configured path, run **Groovy: Restart Language Server** to reload its
metadata.

## Plugin manifest and local libraries

Jenkins plugin IDs and optional versions help gvy select matching metadata. A `plugins.txt` line is `plugin-id` or
`plugin-id:version`; blank lines and `#` comments are ignored. Prefer the IDs and versions from the Jenkins controller
that the team targets. This manifest describes plugin metadata; it is not a shared-library list. With versions, gvy may
resolve plugin artifacts for metadata when they are not already cached, so a versioned manifest alone does not promise
an offline session. The offline example uses names without versions and a saved local GDSL file.

For an explicit manifest path, use `groovy.jenkins.pluginsTxtPath`, for example:

```json
{
  "groovy.jenkins.pluginsTxtPath": "plugins.txt"
}
```

Relative paths are resolved from the workspace root. The server also checks conventional `plugins.txt` locations when
this setting is omitted. A library alias is a separate setting: current support maps it to a compiled JAR and optionally
a sources JAR. Replace the paths below with absolute paths to artifacts you have already prepared:

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

Use the same alias in `@Library('team-library')`. A JAR supplies compiled classes to the Jenkins-file classpath;
`sourcesJar` supplies source for navigation. This configuration does not point at the example's unpacked
`shared-library/` directory, acquire its version, or implement Jenkins's full `vars/`, `src/`, and `resources/` library
loading behavior. For the intended library layout, Jenkins places package-shaped Groovy classes under `src/`, global
scripts under `vars/` (the filename becomes the global name), optional matching help files under `vars/*.txt`, and
resources under `resources/` for external libraries. On Jenkins, administrators associate a library name with its source
and optional default version; a pipeline requests that name with `@Library('team-library') _`. Where Jenkins policy
allows, the annotation may select a version such as `@Library('team-library@release-2') _`. See Jenkins's
[shared-library configuration and directory structure](https://www.jenkins.io/doc/book/pipeline/shared-libraries/#directory-structure).
The current gvy JAR setting maps a configured alias to local artifacts; it does not reproduce Jenkins SCM source,
default-version, or version-override policy.

## CodeNarc for Jenkins files

The default Jenkins CodeNarc profile is intended to include CPS safety and additional correctness checks. Workspace
Groovy rulesets are parsed with a restricted AST and never executed. The currently supported explicit `.codenarc` opt-in
is:

```groovy
ruleset {
    ruleset('rulesets/jenkins.xml')
}
```

This enables only CodeNarc's bundled Jenkins CPS rules; it does **not** include the extra checks from gvy's complete
built-in Jenkins profile. The complete-profile workspace opt-in is currently broken and tracked by
[issue #1477](https://github.com/albertocavalcante/gvy/issues/1477); use the automatic Jenkins profile for the full
built-in checks until that opt-in is repaired. See [diagnostics and formatting](diagnostics-formatting.md) for the
general CodeNarc settings and safety behavior.

## Limits and troubleshooting

- No controller connection, authentication, Pipeline execution, or Jenkins-side validation is performed by gvy.
- Missing or incomplete metadata can leave valid Jenkins names unresolved. Saved GDSL and plugin manifests represent the
  environment from which they were prepared; keep their controller, plugin versions, and preparation date with your
  team's metadata record.
- If a file receives ordinary Groovy behavior, check its name or add a matching `groovy.jenkins.filePatterns` entry.
- If a GDSL contribution is missing, check the absolute path, the machine-scoped settings, and whether the file contains
  supported GDSL declarations. Restart the language server after refreshing it.
- If a custom library symbol is missing, check that its alias matches `@Library`, the configured JAR exists, and the
  source JAR contains source paths corresponding to the compiled classes. A directory path is not supported here.

See the [settings reference](reference-settings.md),
[editor guide](../../editors/code/README.md#jenkins-pipeline-support), and [troubleshooting guide](troubleshooting.md)
for related configuration details.

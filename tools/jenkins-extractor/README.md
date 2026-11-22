<!--
SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
SPDX-License-Identifier: Apache-2.0
-->

# Locked Declarative oracle

This development tool checks handwritten Jenkinsfiles against two exact Jenkins parser/validator environments. It does
not execute Pipeline builds. It is separate from the existing GDSL conversion tool and from the language server.

| Profile   | Jenkins core | Declarative plugin   |
| --------- | ------------ | -------------------- |
| `current` | 2.580.2      | 2.2293.v6e7193cec599 |
| `legacy`  | 2.479.3      | 2.2265.v140e610fe9d5 |

Both graphs also include Docker Pipeline and Kubernetes agents. The checked-in locks contain every selected plugin's
version, SHA-256, download URL, required core and manifest dependency edges. Optional edges are recorded; an optional
dependency is checked when selected by another edge. WAR-packaged detached plugins are inventoried separately. Their
presence in the WAR does not claim they are installed or active.

## Run offline tests

Use Node.js 22 or newer, `unzip`, and `lsof` on Linux or macOS:

```sh
node --test tools/jenkins-extractor/oracle/oracle.test.mjs
```

The existing CI discovers these tests through `tools/ci/jenkins-oracle.test.mjs`.

These tests do not download artifacts, start Java, or contact Jenkins. They cover incomplete graphs, corrupted and
missing inputs, failed startup, timeouts, owned-home cleanup, and detection of a real local TCP listener.

## Run the real oracle

Choose a private cache and a new private evidence directory outside the repository. Set `JAVA` to an absolute JDK 21
Java executable. On shared machines, run the commands inside one `build-slot --` admission and run one profile at a
time. Reserve at least 4 GiB of free space on the cache and evidence volumes.

```sh
node tools/jenkins-extractor/oracle/fetch.mjs current "$CACHE"
node tools/jenkins-extractor/oracle/fetch.mjs legacy "$CACHE"
build-slot -- node tools/jenkins-extractor/oracle/oracle.mjs run current "$CACHE" "$EVIDENCE" "$JAVA"
build-slot -- node tools/jenkins-extractor/oracle/oracle.mjs run legacy "$CACHE" "$EVIDENCE" "$JAVA"
```

An explicit run fails if a locked input is missing, differs from its hash or manifest, cannot boot, leaves a plugin
inactive, violates a dependency minimum, or produces an unexpected control result. There is no skipped-oracle success.

The download helper validates and snapshots the artifact identity before cache access or a network request. Initial URLs
must exactly match the official HTTPS core/plugin paths derived from the validated version and plugin ID; filenames and
SHA-256 digests are checked at the same boundary. A cache hit cannot bypass validation, and later caller mutation cannot
change the download identity. Requests contain no file body or private headers. Jenkins' normal mirror redirects remain
supported; the initial-origin check is not a redirect-domain allowlist or a general network sandbox. Downloaded bytes
still require the exact locked digest before publication.

Each run uses a unique private home, a non-root Java process with a 1 GiB heap, disabled HTTP/HTTPS/HTTP2/control ports,
zero executors, disabled agent listeners, and an empty job/node inventory. The prewritten Jenkins security configuration
uses the no-login realm with a separate authorization strategy denying anonymous access. Before and after conversion,
the harness checks the loaded realm, denied anonymous permissions and absent account-creation methods. The user
inventory may contain only Jenkins' internal `SYSTEM` record, with no password or API token properties enabling login.
The no-login realm avoids the legacy private-realm first-account endpoint, whose lack of a setup-state check is
described in [SECURITY-4122](https://www.jenkins.io/security/advisory/2026-10-28/#SECURITY-4122). The supported
`hudson.cli.CLIAction.ALLOW_WEBSOCKET=false` property disables the WebSocket CLI endpoint as defense in depth. With no
network connector, HTTP CLI is unreachable. The harness invokes `lsof` for its running JVM before and after conversion
and fails on any Internet socket, including UDP or outbound TCP; missing or failed inspection also fails the run. These
are OS observations at two checkpoints, not continuous monitoring or an OS network sandbox. Loopback bind addresses
remain explicit in the disabled connector configuration. No user controller, credentials, jobs, shared libraries, or
workspace plugin code are used. Only the maintained init script is executed. The parent owns the Java process group and
enforces a four-minute timeout; termination and errors remove its home after the entire owned process group drains. A
group that cannot be terminated fails the run and retains its home for recovery. Logs and hash-bound JSON results remain
in the evidence directory. A result is published only after cleanup succeeds. Remove evidence when the corresponding
change no longer needs its review/verification record; retain the reusable cache until these compatibility targets are
retired.

Usage statistics and telemetry are explicitly disabled. The fresh home also defers tool-installer metadata refresh:
Jenkins computes a missing catalog's due time as zero plus its default interval, so the launcher sets that interval to
its launch time plus one hour, beyond the four-minute JVM bound. Both runtime checkpoints require every loaded catalog
to remain deferred. This controls the startup daily-check path; it is not a general network block. An unexpected socket
still fails the run, with bounded private thread-stack diagnostics. No fixture is evaluated before the first checks
pass.

The pinned Winstone launchers create the web application and start Jetty even when no connectors are configured; HTTP,
HTTPS and HTTP2 connector factories return no connector for a negative port. The separate control port is also disabled.
This preserves Jenkins/plugin initialization without exposing a server:

- [Legacy Winstone launcher](https://github.com/jenkinsci/winstone/blob/winstone-8.4/src/main/java/winstone/Launcher.java)
- [Current Winstone launcher](https://github.com/jenkinsci/winstone/blob/8.1053.v85e6c1a_3b_278/src/main/java/winstone/Launcher.java)

## What validation means

The init script waits for Jenkins' completed boot milestone, verifies the exact active graph, and invokes
`Converter.scriptToPipelineDef` on source strings. Upstream's converter compiles through Groovy `CONVERSION`, invokes
`ModelParser.parse(true)`, and returns a Declarative model or parser errors. It does not schedule or execute a Pipeline.

The same call is used by upstream's `ModelConverterAction` validation endpoint:

- [Current converter](https://github.com/jenkinsci/pipeline-model-definition-plugin/blob/6e7193cec599b402ee79ee9afc5b8a417eb43967/pipeline-model-definition/src/main/groovy/org/jenkinsci/plugins/pipeline/modeldefinition/parser/Converter.groovy)
- [Legacy converter](https://github.com/jenkinsci/pipeline-model-definition-plugin/blob/140e610fe9d5290e5efbb75c6da39cfb18ac7c7d/pipeline-model-definition/src/main/groovy/org/jenkinsci/plugins/pipeline/modeldefinition/parser/Converter.groovy)
- [Current endpoint](https://github.com/jenkinsci/pipeline-model-definition-plugin/blob/6e7193cec599b402ee79ee9afc5b8a417eb43967/pipeline-model-definition/src/main/java/org/jenkinsci/plugins/pipeline/modeldefinition/endpoints/ModelConverterAction.java)
- [Legacy endpoint](https://github.com/jenkinsci/pipeline-model-definition-plugin/blob/140e610fe9d5290e5efbb75c6da39cfb18ac7c7d/pipeline-model-definition/src/main/java/org/jenkinsci/plugins/pipeline/modeldefinition/endpoints/ModelConverterAction.java)

Controls are handwritten, with independent expected classifications in `controls.json`: minimal/Docker/Kubernetes
accepted forms, missing-agent and unknown-section rejection, malformed source rejection, and Scripted source with no
Declarative pipeline. A valid file with a top-level throw also checks that source is not executed. These establish
parser/Declarative validation behavior, not CPS execution, sandbox approval, controller-specific values, or build
success. Future DSL suites should add independently justified cases rather than derive expectations from gvy.

## Advisory limits

[Advisory scope and legacy exception](ADVISORIES.md) records exact affected versions, exploit conditions, compatible
fixes, residual uncertainty, ownership and review expiry. A passing parser run does not clear those advisories.

## Regenerate candidate locks

Lock regeneration is a separate, explicit maintainer operation. The runtime consumes existing locks without resolving
dependencies. `roots.json` pins both cores, the root plugins and the plugin manager release. Never substitute the old,
unverifiable `2.2214.v00573e73ddf1` schema provenance.

1. Obtain the exact core WARs and plugin manager release JAR. Use
   `gh release download 2.16.0 --repo
   jenkinsci/plugin-installation-manager-tool --pattern jenkins-plugin-manager-2.16.0.jar --dir "$CACHE"`.
2. Save the official `update-center.actual.json` and `current/plugin-versions.json` into the cache under those
   basenames. Retain these snapshots privately. Their hashes and original URLs are captured in each generated lock;
   rolling URLs may no longer reproduce a historical snapshot.
3. Inside build admission, run
   `node tools/jenkins-extractor/oracle/generate-lock.mjs PROFILE "$CACHE" "$EVIDENCE" "$JAVA"`. It verifies the fixed
   root/tool hashes, uses the saved metadata with the profile's explicit resolution policy, validates downloaded plugin
   hashes against that metadata, and records actual archive manifests.
4. Review every changed artifact and dependency edge, then boot both targets and run the controls. A generated candidate
   is not a verified compatibility profile until the real oracle succeeds.

[Plugin manager behavior and options](https://github.com/jenkinsci/plugin-installation-manager-tool/blob/6ce35762b30e6fb4c128454fd400a67bb68c6dee/README.md)
describe its dependency resolution. The current graph selects latest dependencies while preserving exact root pins; the
legacy graph uses highest-required dependencies with explicitly pinned compatible security updates. Raw resolution logs
retain plugin advisories; review them with the target versions and isolation limits. Do not hide warnings or present
this test controller as a production deployment recommendation.

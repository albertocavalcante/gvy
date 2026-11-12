# Frozen Groovy benchmark corpus

Validate the complete selected corpus without fetching, evaluating Groovy code, or running a compiler:

```bash
node tools/perf/bench/corpus/check.mjs
node --test tools/perf/bench/corpus/validate.test.mjs
```

The command emits a compact JSON integrity report. A missing or changed fixture, license, manifest entry, or declared
profile fails with a fixed error and nonzero exit. The report explicitly states `compatibility: "not-executed"`.

## Selected workloads

`manifest.json` fixes 11 real files totaling 79,808 bytes from six independently maintained projects. Each workload
family has two project origins. These are source examples, libraries, build files, and tests from actual projects; they
are not generated copies presented as corpus diversity.

| Family        | Files | Origins | Selected shape                                                             |
| ------------- | ----: | ------: | -------------------------------------------------------------------------- |
| Class/library |     3 |       2 | Small exception class, concurrent-variable helper, larger Ivy-backed class |
| Script        |     2 |       2 | Groovy development-tool driver and hosted OFBiz service script             |
| Gradle        |     2 |       2 | Spock project build and Geb bootstrap build                                |
| Jenkins       |     2 |       2 | Parallel-step example and Docker project's actual CI pipeline              |
| Spock         |     2 |       2 | Feature-name/embedded-source tests and Geb browser specifications          |

The selected files range from 980 to 30,782 bytes. For this corpus, small is below 2 KiB, medium is 2–16 KiB, and large
is at least 16 KiB. Larger synthetic scaling and malformed/Unicode controls belong to separate named workloads. This
selection does not establish large-workspace coverage. Groovy contributes 45.9% of source bytes; publish that
concentration with results rather than treating all files as independent projects.

All real entries declare `conversion-only-empty-external-classpath`. Native syntax and Groovy CONVERSION cases can use
them after their own acceptance checks. Real compiler-semantic cases need a separately pinned and verified dependency
profile. Spock, Gradle, Ivy, Jenkins and OFBiz dependencies must not appear through ambient user configuration.

The script family includes a hosted service, not two standalone command-line applications. Never execute these sources:
the development driver and CI/build scripts contain real process, filesystem and deployment operations. Parsing source
as data is the benchmark operation.

## Provenance and redistribution

Each fixture records its exact repository commit, upstream path, Git blob SHA-1, SHA-256, byte count and LF-delimited
line count. Byte content, line endings, comments and source notices are preserved. Source license/NOTICE bytes have
their own paths and digests in `licenses/`; keep them with redistributed fixtures.

The version-one source IDs, repositories, SPDX labels and required upstream LICENSE/NOTICE path sets are also fixed in
the validator. Removing a notice declaration together with its file still fails; the manifest cannot silently reduce its
own required licensing evidence.

| Source                                                                                                                    | Pinned revision            | Selected-source license evidence                       |
| ------------------------------------------------------------------------------------------------------------------------- | -------------------------- | ------------------------------------------------------ |
| [Apache Groovy](https://github.com/apache/groovy/tree/24e5e8c8b8b4a62aa919d8bd5ce29474bbac19ad)                           | Groovy 4.0.33 / `24e5e8c8` | Apache-2.0 headers, LICENSE and NOTICE                 |
| [Spock](https://github.com/spockframework/spock/tree/ce7e8afa895210d0b06103223f10d963a83f7a08)                            | `ce7e8afa`                 | Apache-2.0 source/repository terms, LICENSE and NOTICE |
| [Apache Geb](https://github.com/geb/geb/tree/9b26903094accc1c8c392a62ec72d6a35737ca42)                                    | `9b269030`                 | Apache-2.0 headers, LICENSE and NOTICE                 |
| [Apache OFBiz](https://github.com/apache/ofbiz-framework/tree/59b32bdc3bed4abdeb9b5eaa12f5d605a3926985)                   | `59b32bdc`                 | Apache-2.0 header, LICENSE and NOTICE                  |
| [Jenkins pipeline examples](https://github.com/jenkinsci/pipeline-examples/tree/fb9575a8182b51614f5f0df912b46b37d95fbb8d) | `fb9575a8`                 | MIT LICENSE.txt                                        |
| [Jenkins Docker](https://github.com/jenkinsci/docker/tree/56c9f90bd241809337db54b496bf42af421bcf9c)                       | `56c9f90b`                 | MIT LICENSE.txt                                        |

Acquisition used the GitHub CLI contents API at each recorded commit, checked the returned Git blob identity, computed
SHA-256, and verified that digest before writing each file. Future refreshes must repeat those checks and source-notice
review. A repository's detected license alone is insufficient evidence for an attributed third-party subdirectory.

### Preserved upstream whitespace

A full `git diff --check` reports these five observations in four verbatim license files. They are preserved source
bytes, not a passing whitespace check. Do not normalize them to make the command green. Code, manifest and authored
documentation receive a separate whitespace check; no Git attribute or global diagnostic suppression is added.

| Local license path                          | Observation           | Upstream revision/path                                 | SHA-256                                                            |
| ------------------------------------------- | --------------------- | ------------------------------------------------------ | ------------------------------------------------------------------ |
| `licenses/geb/LICENSE:330`                  | New blank line at EOF | `9b26903094accc1c8c392a62ec72d6a35737ca42:LICENSE`     | `f913b21bd9539a9ff3eac2b9249938c54589c6099c39b7993a905f84f3366520` |
| `licenses/geb/NOTICE:7`                     | Trailing space        | `9b26903094accc1c8c392a62ec72d6a35737ca42:NOTICE`      | `9b26006b64531f22c2fe24148e9dc0cfeb13861851aa26a91f994a938fe0c687` |
| `licenses/geb/NOTICE:8`                     | New blank line at EOF | `9b26903094accc1c8c392a62ec72d6a35737ca42:NOTICE`      | `9b26006b64531f22c2fe24148e9dc0cfeb13861851aa26a91f994a938fe0c687` |
| `licenses/jenkins-docker/LICENSE.txt:22`    | New blank line at EOF | `56c9f90bd241809337db54b496bf42af421bcf9c:LICENSE.txt` | `671fb1500837d11678e7eb8d654d1eb04cca84fa97d077839e7480ba76b8173d` |
| `licenses/pipeline-examples/LICENSE.txt:22` | New blank line at EOF | `fb9575a8182b51614f5f0df912b46b37d95fbb8d:LICENSE.txt` | `25e9cc6d3d2e617b94048329269d3cc9af5320eb8b9d2ecc991db0c0fca86900` |

The corpus maintainer owns byte-fidelity review of this narrow list. Each future corpus refresh must verify upstream
bytes and their recorded digests again; any additional diagnostic needs its own assessment.

## Integrity and quality boundaries

The validator requires every version-one fixture, safe relative paths, immutable revision syntax, two origins per
family, the six-category UTF-8 half-open span contract, and bounded regular files. It rejects intermediary links,
missing notices, modified bytes, invalid UTF-8 and undeclared manifest fields. The corpus root must be trusted against
concurrent hostile directory replacement; this is an integrity preflight, not a filesystem sandbox or an atomic
snapshot.

`validateClasspathProfile` checks declarations only. Its mismatch tests do not observe a JVM classpath, prove compiler
phase execution, or resolve dependencies. Runtime identity, actual phases/outcomes and raw-result binding belong to the
owning adapters and shared benchmark-evidence context.

Source integrity does not prove parser acceptance, complete highlighting, recovery quality or performance. Independent
authored span/Unicode/edit oracles form a separate review unit. Do not close the aggregate corpus-and-quality gate from
this data check alone, and do not generate expected outputs from the parser under measurement.

# Frozen Groovy benchmark corpus

Validate the complete selected corpus without fetching, evaluating Groovy code, or running a compiler:

```bash
node tools/perf/bench/corpus/check.mjs
node --test tools/perf/benchmark-corpus.test.mjs
```

The command emits a compact JSON integrity report. A missing or changed fixture, license, manifest entry, or declared
profile fails with a fixed error and nonzero exit. The report explicitly states `compatibility: "not-executed"`.

The test command also requires Git and exercises an isolated `core.autocrlf=true` checkout. All 21 imported files and
the hash-bound manifest retain their frozen hashes, canonical MIT text stays LF, and unlisted neighbors retain ordinary
CRLF conversion. A mixed CRLF/LF control checks preservation at an exact imported path. This test does not fetch source
or run a compiler.

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

Each version-one fixture is also bound to its selected source ID. Project counts use the repositories referenced by
fixtures, so unused declarations cannot inflate diversity and moving fixtures between origins cannot preserve a false
six-project claim.

### Preserved upstream whitespace

A full `git diff --check` reports these five observations in four verbatim license files. They are preserved source
bytes, not a passing whitespace check. Do not normalize them to make the command green. Code, manifest and authored
documentation receive a separate whitespace check. Exact-path `-text` checkout attributes preserve the 21 imported
source/license files and the manifest whose raw bytes define `manifestSha256`; canonical `LICENSES/MIT.txt` has an exact
`text eol=lf` rule. These attributes control byte conversion only. They neither suppress whitespace diagnostics nor
exempt future unlisted files.

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

## Authored quality and edit controls

Run the separately authored quality controls with the integrity checks:

```bash
node --test tools/perf/bench/corpus/validate.test.mjs tools/perf/bench/corpus/quality.test.mjs
```

`authored.json` contains nine small original sources, independently specified lexical spans, four semantic source
anchors, and four deterministic edit traces. Source text is encoded as JSON strings so decoding preserves empty input,
whitespace, CRLF, supplementary characters and combining sequences without Git line-ending conversion. Every source and
edit target has a SHA-256. The expected spans were authored from source, not generated by either candidate parser.

Seven fixtures use `exact` lexical expectations. These compare the whole declared six-category projection of those small
sources; they do not assert variable/type coloring, complete editor rendering or semantic equivalence. The malformed
string and GString/dollar-slashy fixtures use `required` anchors only. Their passing result explicitly has
`exactLexicalSpansChecked: false` and cannot certify complete coloring. Missing punctuation or another genuine candidate
coverage gap must remain visible rather than being removed from the goldens.

`verifySyntaxObservation` requires the fixture ID, source digest, document version, reported outcome and completion to
match before checking spans. It rejects empty/nonempty partial exact results, stale snapshots, wrong categories,
UTF-8-interior endpoints, overlaps and excessive observations. Adapters must resolve engine precedence and project raw
events deliberately before calling it; raw query captures are not resolved highlights. Richer categories and null native
spans are unmapped data, not permission to invent a missing category. Normalization only sorts disjoint spans and merges
touching spans of the same category.

`byteOffsetToUtf16Position` and `utf16PositionToByteOffset` map scalar boundaries to zero-based LSP UTF-16 positions.
They reject the middle of a surrogate pair and the interior of CRLF, which have no corresponding scalar byte/LSP
boundary. `byteOffsetToTreeSitterPoint` uses UTF-8 byte columns and advances rows only for LF; it can represent the CRLF
interior. LSP line mapping also recognizes a lone CR. No Unicode normalization is performed, so composed and decomposed
text keep different identities.

`applyByteEdit` requires the exact original digest and document version, increasing target version, valid byte
boundaries and a changed result. It rejects stale/repeated edits and no-op samples. The frozen traces repair an
unterminated string, rename a multibyte identifier, replace CRLF with LF and insert an unrelated prefix. Their target
texts and digests are independently specified. Each replay starts from its declared initial fixture.

All helpers are pure and bounded to 1 MiB of decoded source and 65,536 lexical spans. An oracle/profile declaration is
not execution evidence: these tests neither invoke Groovy nor prove a native parser's acceptance or highlights. The four
`semantic-core` anchors are metadata for later real compiler/provider tests; `semanticChecked` is always false here.
Adapters still need actual requested/achieved compiler outcomes, phase/classpath identities, full output collection and
edit-versus-fresh execution before claiming an observed correct benchmark result.

# Prebuilt facts experiment

**Status:** Proposed, 2026-11-27. Tracking issue: [#2516](https://github.com/albertocavalcante/gvy/issues/2516), under
[#1573](https://github.com/albertocavalcante/gvy/issues/1573). This is a pre-registration: the result cannot move what
is fixed here before any measurement.

The server recomputes facts about the JDK, the Groovy runtime and dependency jars on every start, and it reads the
runtime it runs on rather than the project's. The idea is one immutable facts file per artifact, identified by the
SHA-256 of the input and a format version. The gate: nothing beyond the library modules and the harness is built until
the thresholds below are met.

## Questions

- **Q1:** How large is the compressed facts file relative to its input?
- **Q2:** How long does building it take?
- **Q3:** How long until a lookup works, against today's mechanism?
- **Q4:** How much heap and Metaspace stay retained?
- **Q5:** Are class names identical; how do extension method sets differ?

## Inputs

Unit tests download nothing ([test rule](INCREMENTAL_REPARSE_DELIVERY_PLAN.md)); only the harness resolves these.

| Artifact                                                        | Why                             | How obtained                                                                                                   |
| --------------------------------------------------------------- | ------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| JDK 21 (the build toolchain)                                    | Expected largest gain           | `jrt:/` of the harness JVM; hash of `lib/modules` plus runtime version                                         |
| Groovy 4.0.33 core, macro, json, xml, templates                 | The server's own Groovy         | Core, macro, json: version catalog. xml, templates: harness-only configuration from Maven Central, like 2.4.21 |
| Groovy 2.4.21 core and groovy-nio                               | Older line Jenkins pins         | Harness-only configuration from Maven Central (declared in `settings.gradle.kts`)                              |
| asm, lsp4j, kotlin-stdlib, jackson-databind, gradle-tooling-api | Small to large; Java and Kotlin | Version catalog                                                                                                |

The groovy-nio descriptor is unverified; slice 3 confirms it.

**Jenkins plugin archives are out of scope.** [#2148](https://github.com/albertocavalcante/gvy/pull/2148) is open and
rewrites the step model a plugin section would store. Plugin metadata belongs to a separate SDK family
([extraction plan](SDK_EXTRACTION_DELIVERY_PLAN.md)). Plugin archives come from a repository `settings.gradle.kts` does
not declare (AGENTS.md rule 12). Steps need annotation meaning, which the format leaves out. Once #2148 merges, a
plugin's main jar can join as one more library row, signatures only.

## Today's mechanism on the same input

| Facts             | Today                                                                                                 |
| ----------------- | ----------------------------------------------------------------------------------------------------- |
| Class names       | `JvmClasspathIndex` (header-only reader since #2431), then the name index built in `ClasspathService` |
| Members           | `ClasspathReflection` over a `URLClassLoader`; one fixed list of up to 1,000 public classes           |
| Groovy extensions | `GroovyGdkProvider`, 4.0.33 only                                                                      |

JDK modules are always in today's index, so sets are cumulative: JDK, plus Groovy 4.0.33, plus libraries. "Today" means
the `ClasspathService`, `ClasspathReflection` and `GroovyGdkProvider` code at the commit recorded in the run manifest;
both mechanisms are measured at that same commit, and a run whose two mechanisms were measured at different commits is
invalid.

## Measurements and method

Per input and mechanism: raw bytes and Deflater levels 1, 6, 9; build time; cold and warm load; retained heap and
Metaspace; input hashing time, reported separately.

- **Timing:** JMH, already in the build. Cold: `SingleShotTime`, 15 forks, no warm-up (what a start pays). Warm:
  `AverageTime`, 5 forks, 5 warm-up and 10 measured iterations. Flags `-Xmx1g -XX:+UseG1GC`. If fork noise exceeds 20%
  CV on small jars, group them into one parameter, only before any threshold is evaluated, for both mechanisms, and
  recorded in the run manifest; otherwise not allowed.
- **Heap:** a separate `main`: fresh JVM per input, 5 runs, median, after three GCs while holding the result.
- **Noise rule:** each timing carries the 99.9% confidence interval the harness reports. Speedup is (lower bound of
  today) ÷ (upper bound of facts); time saved is (lower bound of today) − (upper bound of facts). Each metric is
  evaluated per platform: arm64 macOS and GitHub-hosted `ubuntu-24.04` x64. Outcomes are ordered Stop < Continue < Also.
  The lower platform outcome stands per metric; the lowest metric decides. Size and retained memory have no interval:
  size is a deterministic ratio, and memory uses the median of its 5 runs.
- **Extending `tools/perf/evidence.mjs`:** it is a closed envelope for language-server sessions with no byte or heap
  fields; extending it needs its own review, so this work does not.

## Decision thresholds

Measured on JDK + Groovy 4.0.33 + libraries. Sizes are evaluated at Deflater level 6, as an aggregate: sum of compressed
facts bytes ÷ sum of input bytes (for the JDK, the input is the size of `lib/modules`). Levels 1 and 9 are reported but
decide nothing. Bundled-tier size caps use level 6 too. The bundled column applies only if every Continue condition also
holds. The Groovy 2.4.21 jars are outside the aggregate; their sizes are reported only. "Today" in a ratio is the
baseline mechanism defined above.

| Metric                                       | Continue: local cache                                                       | Also: bundled tier                                                                                                         | Stop: correctness-only                                         |
| -------------------------------------------- | --------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| Cold speedup: today ÷ (facts load + lookups) | at least 3x and at least 200 ms saved                                       | JDK alone at least 5x and at least 200 ms saved                                                                            | under 3x or under 200 ms                                       |
| Compressed facts ÷ input                     | at most 25%                                                                 | JDK at most 6 MiB per release; Groovy release at most 1.5 MiB; projected bundle (3 JDK LTS, 5 Groovy lines) at most 25 MiB | over 25% aggregate                                             |
| Retained heap + Metaspace ÷ today            | at most 1.25x                                                               | same                                                                                                                       | over 2x (1.25x to 2x: redesign with lazy decoding, re-measure) |
| Miss cost: (build + write) ÷ today           | at most 2x; above, build off the request path                               | n/a                                                                                                                        | n/a                                                            |
| Correctness                                  | Names identical; extension set contains today's, every difference explained | same                                                                                                                       | Any unexplained difference blocks every column                 |

Groovy 2.4.21 has no baseline, so it is outside the correctness gate; its difference is reported for information.

Three cells need a rule to fit the ordering. Retained memory between 1.25x and 2x counts as Stop for that run; a
re-measure after a redesign is a new run. An unexplained correctness difference is Stop. Miss cost never changes the
outcome: above 2x it only requires building off the request path.

**Correctness-only** means: keep slices 1-3 and use version-accurate extension facts built in memory. Build no cache,
bundle or feed.

## Run rules

- Slice 4b's author runs it.
- Raw harness JSON, the sizes/heap JSON and the manifest (commit, JDK, runner image, corpus hashes, regrouping) are
  committed beside the result record in `kb/evidence/prebuilt-facts/`. The repo has no committed-evidence directory yet,
  so this one is new.
- A run is invalid if commit, JDK, runner image or corpus differ from the manifest or between mechanisms.
- Thresholds are never edited after this record merges. An amendment is a new dated section merged before the run it
  affects.

## Format

The minimal format for the experiment, not a frozen one: a packed binary using only the JDK, because Gradle and Bazel
pin different Wire versions, Buck2 has no schema toolchain, and per-message objects would add allocation to the load
being measured.

| Part        | Content                                                                                   |
| ----------- | ----------------------------------------------------------------------------------------- |
| Header      | Magic, versions, input kind, input SHA-256 and length, section table with CRC32C          |
| NAMES       | Packages; classes with package, simple name, outer class, flags, multi-release version    |
| TYPES       | Supertypes; public and protected members, optional generic signature behind a writer flag |
| EXTENSIONS  | Per holder class: instance or static, source, module, version; methods derive from TYPES  |
| Identity    | Qualified by input SHA-256 across files; classpath order is the caller's job              |
| Compression | `NONE` or `Deflater` per section, so NAMES loads without inflating TYPES                  |
| Left out    | Docs, positions, most annotations, constants, private members                             |
| Versions    | Unknown major version rejected; unknown sections skipped                                  |

## Module placement

- `:jvm-metadata:facts`: model, writer, reader. Kotlin stdlib and JDK only, `explicitApi()` like `source`. Its closure
  stays empty: it runs in the server.
- `:jvm-metadata:facts-builder`: jar, `jrt:` image or descriptor to model, with ASM.

Both are the class-file backend of the JVM metadata SDK in the [extraction plan](SDK_EXTRACTION_DELIVERY_PLAN.md); the
harness lives in `gls/src/jmh`.

| Build system | Needs                                                                                                                           |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------- |
| Gradle       | Two `include` lines and two `build.gradle.kts` files (template: `source/build.gradle.kts`); the harness is Gradle-only          |
| Bazel        | `kt_library` and `kt_test` as in `source/BUILD.bazel`; the builder adds the ASM artifact as `semantics/native/BUILD.bazel` does |
| Buck2        | `facts` only, as in `source/BUCK`; ASM is not pinned for Buck2, so the builder is excluded                                      |

## Slices

| #  | Outcome                                            | Before the result?   |
| -- | -------------------------------------------------- | -------------------- |
| 0  | This record                                        | Yes                  |
| 1  | `facts`: round trip, typed rejections              | Yes                  |
| 2  | `facts-builder` for jars and `jrt:`                | Yes                  |
| 3  | Extension facts from both descriptor paths         | Yes                  |
| 4a | Harness in `gls/src/jmh`, smoke run on fixtures    | Yes                  |
| 4b | Results and the decision, with raw data            | It is the result     |
| 5  | Flagged facts-backed class-name index, default off | Waits for "continue" |

## Risks

| Risk                   | Handling                                                                                                                                                                                                                                              |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Mixed-version contract | Fits packet M3.3 of the [incremental plan](INCREMENTAL_REPARSE_DELIVERY_PLAN.md): facts are qualified by input hash and combined per context, never as a global union; missing facts are unavailable, never a silent fall back to the server's Groovy |
| Untrusted file input   | Size and ratio caps; counts checked before allocating; indices validated once at load; strict UTF-8; symlinks refused; no name from a file reaches a class loader; typed rejections                                                                   |
| Determinism            | Sorted, no timestamps: same input, producer and format give identical raw bytes (over raw sections; Deflate output can vary)                                                                                                                          |
| Hit and miss reporting | The reader returns `Loaded` or `Rejected` with a reason; these map to later counters in [#1562](https://github.com/albertocavalcante/gvy/issues/1562)                                                                                                 |

## Not decided here

Each needs its own issue after the result: a local facts cache, facts shipped with the server, any published feed. The
experiment redistributes nothing; a review of OpenJDK and per-library terms is required before any facts are shipped.

## 2026-11-27 amendment: two string pools, names and types

Made while implementing slice 1, before any measurement run; no threshold, input or earlier text changes.

The format has two sorted, unique string pools instead of one: a names pool, used by the names and extensions sections,
and a type pool, used only by the types section. The types section names the class an entry describes by its position in
the names section's class table. Reason: Q3 measures how long until a class-name lookup works. With one pool, loading
names would also decode every descriptor, signature and parameter name that only the types need, which would charge the
facts mechanism for work the names lookup does not use. A string both groups use, such as a superclass name that is also
a class, now appears in each pool, which costs some size; Q1 measures that cost. The reader also bounds the objects it
decodes, not only the bytes it inflates (one decoded-item budget per file). `jvm-metadata/facts/FORMAT.md` is the
specification.

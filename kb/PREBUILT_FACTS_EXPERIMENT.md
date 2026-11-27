# Prebuilt facts experiment

**Status:** Proposed, 2026-11-27. Tracking issue: [#2516](https://github.com/albertocavalcante/gvy/issues/2516), under
[#1573](https://github.com/albertocavalcante/gvy/issues/1573). This is a pre-registration: questions, inputs, method and
thresholds are fixed before any measurement, so the result cannot move them.

The server recomputes facts about the JDK, the Groovy runtime and dependency jars on every start, and it reads the
runtime it runs on rather than the project's. The idea is one immutable facts file per artifact, identified by the
SHA-256 of the input and a format version. The gate: nothing beyond the library modules and the harness is built until
the thresholds below are met.

## Questions

- **Q1:** How large is the compressed facts file relative to its input?
- **Q2:** How long does building it take?
- **Q3:** How long until a lookup works, against today's mechanism on the same input?
- **Q4:** How much heap and Metaspace stay retained?
- **Q5:** Are class names identical, and how do the Groovy extension method sets differ?

## Inputs

Unit tests download nothing (see the [test rule](INCREMENTAL_REPARSE_DELIVERY_PLAN.md)); only the harness resolves
these.

| Artifact                                                        | Why                             | How obtained                                                                              |
| --------------------------------------------------------------- | ------------------------------- | ----------------------------------------------------------------------------------------- |
| JDK 21 (the build toolchain)                                    | Expected largest gain           | `jrt:/` of the harness JVM; hash of `lib/modules` plus runtime version                    |
| Groovy 4.0.33 core, macro, json, xml, templates                 | The server's own Groovy         | Version catalog                                                                           |
| Groovy 2.4.21 core and groovy-nio                               | Older line Jenkins pins         | Harness-only configuration from Maven Central (already declared in `settings.gradle.kts`) |
| asm, lsp4j, kotlin-stdlib, jackson-databind, gradle-tooling-api | Small to large; Java and Kotlin | Version catalog                                                                           |

The groovy-nio extension descriptor is unverified; slice 3 confirms it.

**Jenkins plugin archives are out of scope.** [#2148](https://github.com/albertocavalcante/gvy/pull/2148) is open and
rewrites the step model a plugin section would store. Plugin metadata belongs to a separate SDK family in the
[extraction plan](SDK_EXTRACTION_DELIVERY_PLAN.md). Plugin archives come from a repository `settings.gradle.kts` does
not declare, and repositories are declared only there (AGENTS.md rule 12). Steps need annotation meaning, which this
format leaves out. Once #2148 merges, a plugin's main jar can join as one more library row, signatures only.

## Today's mechanism on the same input

| Facts             | Today                                                                                                        |
| ----------------- | ------------------------------------------------------------------------------------------------------------ |
| Class names       | `JvmClasspathIndex` (header-only reader since #2431), then the name index built in `ClasspathService`        |
| Members           | `ClasspathReflection` over a `URLClassLoader`; one fixed list of up to 1,000 public classes per fork         |
| Groovy extensions | `GroovyGdkProvider`, 4.0.33 only; loading is parent-first, so 2.4.21 cannot be reflected and reports Q5 only |

JDK modules are always in today's index, so sets are cumulative: JDK; JDK + Groovy 4.0.33; JDK + Groovy + libraries.
Facts cost is load plus the same lookups.

## Measurements and method

Per input and mechanism: raw bytes and Deflater levels 1, 6, 9 per section; build time; cold and warm load; retained
heap and Metaspace; input hashing time, reported separately.

- **Timing:** JMH, already in the build. Cold: `SingleShotTime`, 15 forks, no warm-up (what a start pays). Warm:
  `AverageTime`, 5 forks, 5 warm-up and 10 measured iterations. Flags `-Xmx1g -XX:+UseG1GC`. If fork noise exceeds 20%
  CV on small jars, group them into one parameter.
- **Heap:** a separate `main`: fresh JVM per input, 5 runs, median, after three GCs while holding the result.
- **Noise rule:** a threshold passes only at the conservative 99.9% confidence bound of both medians, on an arm64 Mac
  and a Linux x64 CI runner. If they disagree, the lower outcome wins.
- **Raw results:** committed beside the result document (JMH JSON, sizes/heap JSON, manifest with commit, JDK, OS/arch,
  input hashes). `tools/perf/evidence.mjs` is a closed envelope for language-server sessions with no byte or heap
  fields; extending it needs its own review, so this work does not.

## Decision thresholds

Measured on the set JDK + Groovy + libraries.

| Metric                                 | Continue: local cache                                                       | Also: bundled tier                                                                                                         | Stop: correctness-only                                         |
| -------------------------------------- | --------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| Cold speedup: today ÷ (load + lookups) | at least 3x and at least 200 ms saved                                       | JDK alone at least 5x                                                                                                      | under 3x or under 200 ms                                       |
| Compressed facts ÷ input               | at most 25%                                                                 | JDK at most 6 MiB per release; Groovy release at most 1.5 MiB; projected bundle (3 JDK LTS, 5 Groovy lines) at most 25 MiB | over 25% aggregate                                             |
| Retained heap + Metaspace ÷ today      | at most 1.25x                                                               | same                                                                                                                       | over 2x (1.25x to 2x: redesign with lazy decoding, re-measure) |
| Miss cost: (build + write) ÷ today     | at most 2x; above, build off the request path                               | n/a                                                                                                                        | n/a                                                            |
| Correctness                            | Names identical; extension set contains today's, every difference explained | same                                                                                                                       | Any unexplained difference blocks every column                 |

**Correctness-only** means: keep slices 1-3 and use version-accurate extension facts built in memory. Build no cache,
bundle or feed.

## Format

The minimal format for the experiment, not a frozen one. It is a packed binary using only the JDK: Gradle and Bazel pin
different Wire versions, Buck2 has no schema toolchain, and per-message objects would add allocation to the load time
being measured. If a non-JVM reader needs facts first, move to a `.proto` in `schemas/`.

| Part        | Content                                                                                                                                                                  |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Header      | Magic `GVYF`, format version, producer version, input kind, informational coordinate (never a key), input SHA-256 and length, section table (id, codec, lengths, CRC32C) |
| STRINGS     | One sorted, unique UTF-8 pool; descriptors pooled whole                                                                                                                  |
| NAMES       | Packages (module for the JDK); classes with package, simple name, outer class, flags, multi-release version                                                              |
| TYPES       | Supertypes; public and protected members with flags, optional parameter names, optional generic signature behind a writer flag                                           |
| EXTENSIONS  | Per holder class: instance or static, source (descriptor or Groovy's core list), module, version; methods derive from TYPES                                              |
| Identity    | Inside a file, the JVM's own; across files, qualified by input SHA-256; classpath order is the caller's job                                                              |
| Compression | `NONE` or `Deflater` per section, so NAMES loads without inflating TYPES; if inflating exceeds 30% of load, decide a native codec separately                             |
| Left out    | Docs, source positions, most annotations, constants, Kotlin metadata, private and package-private members                                                                |
| Versions    | Unknown major version rejected; unknown section ids skipped                                                                                                              |

## Module placement

- `:jvm-metadata:facts`: model, writer, reader. Kotlin stdlib and JDK only, `explicitApi()` like `source`. It runs in
  the server and must build everywhere, so its closure stays empty.
- `:jvm-metadata:facts-builder`: jar, `jrt:` image or descriptor to model, with ASM. Depends on `facts`.

Both sit in the class-file backend of the JVM metadata SDK in the [extraction plan](SDK_EXTRACTION_DELIVERY_PLAN.md).
Libraries never import the product, so the harness lives in `gls/src/jmh`.

| Build system | Needs                                                                                                                           |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------- |
| Gradle       | Two `include` lines and two `build.gradle.kts` files (template: `source/build.gradle.kts`)                                      |
| Bazel        | `kt_library` and `kt_test` as in `source/BUILD.bazel`; the builder adds the ASM artifact as `semantics/native/BUILD.bazel` does |
| Buck2        | `facts` only, as in `source/BUCK`; the builder is excluded because ASM is not pinned for Buck2, stated in that PR               |

The harness is Gradle-only.

## Slices

| #  | Outcome                                                                                              | Before the result?   |
| -- | ---------------------------------------------------------------------------------------------------- | -------------------- |
| 0  | This record                                                                                          | Yes                  |
| 1  | `facts`: round trip, typed rejections (version, truncation, CRC, inflate bomb, index)                | Yes                  |
| 2  | `facts-builder` for jars and `jrt:`; fixtures generated in tests; entry order immaterial             | Yes                  |
| 3  | Extension facts from both descriptor paths; malformed descriptors recorded, not fatal                | Yes                  |
| 4a | Harness in `gls/src/jmh`, smoke run on fixtures                                                      | Yes                  |
| 4b | Results and the decision, with raw data                                                              | It is the result     |
| 5  | Flagged facts-backed class-name index, default off, at the per-generation seam in `ClasspathService` | Waits for "continue" |

## Risks

| Risk                   | Handling                                                                                                                                                                                                                                                                                          |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Mixed-version contract | Fits packet M3.3 of the [incremental plan](INCREMENTAL_REPARSE_DELIVERY_PLAN.md). Facts are qualified by input hash and combined per context, never as a global union; missing facts are unavailable, never a silent fall back to the server's Groovy. Slice 5 uses the server's own JDK as today |
| Untrusted file input   | Caps on file and section size; inflate into the declared length with a ratio cap of 50; counts checked before allocating; indices validated once at load; strict UTF-8; symlinks refused; no name from a file reaches a class loader; typed rejections, never `null`                              |
| Determinism            | Sorted, no timestamps: same input, producer and format give identical raw bytes. Deflate output can vary with the zlib build, so reproducibility is defined over raw sections                                                                                                                     |
| Licensing              | The experiment redistributes nothing. Before any bundled tier: review OpenJDK and per-library terms, include no documentation text, keep fixtures generated                                                                                                                                       |
| Hit and miss reporting | The reader returns `Loaded` or `Rejected` (format version, input mismatch, corrupt, oversized); the cache layer adds not present. These map to later counters in [#1562](https://github.com/albertocavalcante/gvy/issues/1562); no paths or coordinates                                           |
| Cache root             | Slice 5 takes its root as a parameter until the helper in [#1575](https://github.com/albertocavalcante/gvy/issues/1575) lands                                                                                                                                                                     |
| Network                | Any later feed defaults to cache-only ([network policy](network-policy-contract.md))                                                                                                                                                                                                              |

## Not decided here

Each needs its own issue after the result: a local facts cache, facts shipped with the server, any published feed.

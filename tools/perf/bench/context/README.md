# Benchmark context declarations

Validate bounded native/JVM context declarations and bind raw bytes to their canonical identity. These pure functions
perform no file access, process launch, persistence, timing or comparison. This is the B03a foundation; B03 remains open
until an observed producer and the shared writer adapter pass their own gates.

Run `node --test tools/perf/benchmark-context.test.mjs`. The existing root profiling test glob discovers the same
controls.

`canonicalizeContext` accepts at most 32 KiB of UTF-8 JSON in a Buffer. It checks a closed versioned field set and
returns deterministically ordered JSON bytes. Native and JVM records have different typed runtime fields. Native
grammars retain both raw and effective query identities. JVM records include the exact numeric build, distribution and
binary identities, heap, collector and selected typed settings. Unknown state remains explicit; no raw paths, arguments
or environments are fields.

`bindRawArtifact` binds at most 16 MiB of opaque bytes to that context using SHA-256 and byte length.
`verifyRawArtifact` checks the binding against supplied bytes and context. Both use fixed error codes. A raw-kind label
does not validate its format, operation counts, source provenance, successful work or correctness. Format-specific
readers must establish those properties before a run can become comparison-ready.

Context values are declarations supplied by a caller. Structural validation and matching hashes do not prove an observed
runtime, a quiet host, equal useful output or reproducibility. Dirty trees, unknown settings and mismatched measurement
boundaries require an explicit decision by later observation/comparison contracts. CPU identity hashes must come from
public machine information; digesting hidden launch settings or secrets cannot certify equivalence.

This version covers P/H/F/E/C declarations for Gvy and tree-sitter. TextMate/Node syntax lane S requires a reviewed
schema extension before use. Full process/parser/workspace/query/line-state observation, raw-format readers,
output-quality results, completed-attempt accounting, limits/retention and the canonical private writer bridge remain
separate B03 work. The module exposes no `comparable`, `correctnessPassed` or other producer-supplied certification
switch.

## Pure JMH raw reader

`readJmhRaw(contextBytes, bindingBytes, rawBytes, planBytes)` accepts four Buffers. It first verifies the B03a raw-byte
binding and requires its kind to be `jmh-json`, a JVM declaration and `instrumentation: "none"`. It then validates the
closed `jmh-1.37-avgt-ns-op-v1` profile for the P/parse lane. It consumes the canonical context bytes returned by B03a,
including that seam's existing JSON interpretation, instead of separately reinterpreting the original declaration. It
performs no IO, launches no process and writes no evidence.

**No actual JMH smoke has been observed for this reader.** The independent literal test exercises the emitted schema
from
[JMH 1.37 JSONResultFormat](https://github.com/openjdk/jmh/blob/2effa2c8310e1d3ad03c8ee02024edca9252b46a/jmh-core/src/main/java/org/openjdk/jmh/results/format/JSONResultFormat.java).
Adopting this format still requires an actual B04 packaged-JMH smoke and the later shared-writer integration. This
reader does not close aggregate B03.

The plan has exactly these fields; the sample names are format examples:

```json
{
  "schemaVersion": 1,
  "profile": "jmh-1.37-avgt-ns-op-v1",
  "forks": 2,
  "warmupIterations": 2,
  "warmupTime": "1 s",
  "measurementIterations": 2,
  "measurementTime": "100 ms",
  "cases": [
    {
      "benchmark": "org.example.ParserBench.convert",
      "params": { "fixture": "tiny" }
    }
  ]
}
```

Each case names the exact public benchmark and complete public parameter combination. Parameter key order and raw row
order do not change case identity. Missing, duplicate, extra or changed cases fail. A case without parameters uses
`params: {}` in the plan and omits `params` in raw JMH JSON, as the writer does.

For this narrow P/JMH profile, `context.workload.scenarioSha256` must be SHA-256 of the **exact bounded public
planBytes**, after the plan has passed its closed-field validation. This is the scenario definition for this profile,
not a mapping from a broader trace/scenario digest. Changing any byte, including whitespace or key order, requires a new
context identity and raw binding. Equivalent parsed fields can match cases, but they do not replace exact-byte plan
identity. A caller cannot reduce the expected case set or substitute a same-length plan while retaining the declared
scenario identity. Other scenario definitions or lanes require a reviewed profile extension. This binding does not prove
the plan was executed, independently approved or correct.

This profile requires JMH `1.37`, mode `avgt`, score unit `ns/op`, one thread, warmup and measurement batch size one,
empty secondary metrics and complete `rawData`. The outer array must have exactly the planned fork count; every group
must have exactly the planned measurement iteration count. Other modes, units, versions, histogram formats, profilers
and extensions fail explicitly; they need a reviewed profile extension. Unknown fields and producer certification
switches are rejected throughout the plan and raw records.

The returned value has only this structure, in plan order:

```json
{
  "schemaVersion": 1,
  "scope": "jmh-raw-structural-only",
  "cases": [
    { "caseIndex": 0, "forkGroups": [[10, 11], [20, 21]] }
  ]
}
```

The numbers are JMH iteration mean times in ns/op, grouped as emitted. The reader never flattens forks, derives tails
from iteration means, returns summary percentiles or computes statistical comparisons. Fork groups are structural groups
in producer JSON; they do not prove independent JVM processes actually ran. The summary fields are checked for shape and
numeric bounds, then discarded. Their statistical consistency is not certified. JMH's string `"NaN"` is accepted only
for paired score error/confidence uncertainty with fewer than three total raw iteration samples;
[JMH's statistics implementation](https://github.com/openjdk/jmh/blob/2effa2c8310e1d3ad03c8ee02024edca9252b46a/jmh-core/src/main/java/org/openjdk/jmh/util/AbstractStatistics.java)
returns undefined uncertainty there. Raw samples and scores must always be finite and positive. Infinity strings, mixed
uncertainty and nonfinite percentiles fail.

Zero warmup is accepted for wiring smoke. It does not establish a hot JVM or make a run eligible for a baseline. A
successful read establishes only the declared structure, byte binding and supported declaration cross-checks. It does
not observe execution, runtime binaries, effective VM defaults, useful output, requested compiler phase, host state,
quality, adequate warmup or comparability. No producer-supplied pass/comparable flag can change this scope.

### Bounds and private metadata

- Context and plan: at most 32 KiB each; binding: 1 KiB; raw: 16 MiB. Inputs must be nonempty Buffers. Context input
  follows B03a's canonicalization contract; its original duplicate-key interpretation belongs to that existing seam.
  Raw, plan and binding bytes require valid UTF-8 JSON without a BOM. A small prescan rejects duplicate keys, including
  escaped aliases, depth above 12, more than 32 keys per object or more than 262,144 structural/string tokens before
  standard `JSON.parse` allocates those structures. JSON grammar remains the standard parser's responsibility.
- At most 32 cases, 32 forks, 100 warmup iterations, 100 measurement iterations and 65,536 total raw samples. At least
  one case, fork and measurement iteration are required. Times use exact positive integer `"N ms"` or `"N s"` spelling
  and cannot exceed one hour per iteration. Equal durations with different spellings still mismatch the plan;
  [TimeValue](https://github.com/openjdk/jmh/blob/2effa2c8310e1d3ad03c8ee02024edca9252b46a/jmh-core/src/main/java/org/openjdk/jmh/runner/options/TimeValue.java)
  supplies these emitted strings. Scores, percentiles and raw means are positive and at most `1e15`; finite error is
  nonnegative; confidence bounds can be negative within `±1e15` and must be ordered.
- Public benchmark identifiers are Java identifier segments separated by dots, at most 256 characters. Each plan case
  has at most 32 public parameter keys of at most 64 identifier characters; values have at most 128 ASCII
  letters/digits/dot/underscore/plus/minus characters and start with a letter or digit. Paths and arbitrary strings do
  not belong in the public plan. Unsupported parameter grammars require a reviewed extension.
- Private `jvm`, version and VM-name strings are bounded to 4 KiB, valid Unicode scalars and no control characters.
  `jvmArgs` has at most 128 such strings. The reader returns no raw path, argument, benchmark/parameter string, VM
  string or digest of private launch settings. Every failure is the fixed `INVALID_JMH_RAW`, without a raw cause.
- Initial metadata support is JDK 17+ HotSpot. Supported names are `OpenJDK 64-Bit Server VM` and
  `Java HotSpot(TM) 64-Bit Server VM`. Numeric JDK version components, VM build and early-access status must match the
  typed context. Java's `java.version` may omit the build; `java.vm.version` must supply it in the supported grammar. VM
  names never establish vendor, distribution or binary digest identity.
- The supported argument grammar is `-Xms`/`-Xmx` with positive integer bytes or binary `k/m/g` suffixes; one
  `-XX:+Use...GC` selector for G1, Serial, Parallel, Z or Shenandoah; `-XX:+/-TieredCompilation`,
  `-XX:+/-BackgroundCompilation`; and `-Xshare:auto` or `-Xshare:off`. Present flags must match known typed values.
  Repeated typed settings, unsupported flags, alternate VM names/version forms and contradictory metadata fail.
  `-Xshare:on`, application archive arguments, system properties and metaspace flags are outside this initial grammar. A
  typed `unknown` value permits either supported choice but does not waive argument parsing. Missing flags leave
  effective settings unverified.

Run `node --test tools/perf/benchmark-context.test.mjs` for context and raw-reader controls; the canonical
`node --test tools/perf/*.test.mjs` command discovers both through that wrapper.

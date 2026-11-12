# Benchmark context declarations

Validate bounded native/JVM context declarations and bind raw bytes to their canonical identity. These pure functions
perform no file access, process launch, persistence, timing or comparison. This is the B03a foundation; B03 remains open
until an observed producer and the shared writer adapter pass their own gates.

Run `node --test tools/perf/benchmark-context.test.mjs`. The existing root profiling test glob discovers the same
controls.

`canonicalizeContext` accepts at most 32 KiB of UTF-8 JSON in a Buffer. It checks a closed versioned field set and
returns deterministically ordered JSON bytes. Native and JVM records have different typed runtime fields. Native
grammars retain both raw and effective query identities. JVM records include the exact numeric build, runtime binary
identities, heap, collector and selected typed settings. Unknown state remains explicit; no raw paths, arguments or
environments are fields.

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

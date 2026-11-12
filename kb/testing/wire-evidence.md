# Inspecting original protocol evidence

SDK objects and feature projections are derived views. This helper accepts a supplied original byte prefix encoded as
base64. Client-boundary capture and its integration remain separate work tracked in
[#1717](https://github.com/albertocavalcante/gvy/issues/1717); this inspector does not install a capture producer. Keep
supplied evidence private: it can contain workspace text, paths and server messages.

The offline helper is exported by [wire-evidence.mjs](../../tools/perf/wire-evidence.mjs). It has no process, network,
logging or file-writing effects. Pass the original base64 prefix to its inspectWirePrefix function. The returned graph
is immutable and retains the entire input as prefixBase64, including bytes the inspector cannot interpret.

## Reading the result

The live logging driver uses [lsp-protocol.mjs](../../tools/logging/lsp-protocol.mjs) for its request lifecycle and
decoded messages. The offline inspector has a different evidence contract: it retains original body bytes and JSON
lexemes. Serializing a decoded message again cannot recover duplicate members or rounded numeric IDs. Do not use this
helper as another live client.

| Field                 | Meaning                                                                                      |
| --------------------- | -------------------------------------------------------------------------------------------- |
| boundary              | aligned, partial, invalid or limited framing at the end of the inspected prefix              |
| consumedBytes         | Bytes belonging to the complete frames returned                                              |
| frames[].headerBase64 | Original header bytes, excluding the four-byte CRLF CRLF separator                           |
| frames[].bodyBase64   | Original body bytes, independent of SDK decoding                                             |
| frames[].fields       | Root member names and original value JSON lexemes, without numeric conversion                |
| frames[].id           | Observed ID kind and its original lexeme; unknown when inspection is incomplete or ambiguous |
| frames[].bodyIssue    | Invalid UTF-8/JSON, duplicate members, conflicting response members or an inspection limit   |

An aligned prefix says nothing about EOF, peer delivery, successful checks or complete session capture. The caller must
separately inspect capture gaps, in-flight operations and terminal execution/cleanup evidence. Truncation and missing
prerequisites must remain visible in any report or replay.

The helper preserves duplicate members instead of overwriting them. Missing members differ from explicit null; numeric
IDs stay original JSON lexemes, including values beyond JavaScript's exact integer range. Result keys such as left and
right remain ordinary data. Request/notification/response shapes are structural hints, not validation of the complete
JSON-RPC envelope or a method's LSP schema.

## Bounds and invalid input

The default limits are also hard ceilings; callers may lower them.

| Limit                       | Maximum |
| --------------------------- | ------- |
| Decoded input prefix        | 1 MiB   |
| Complete frames             | 256     |
| Header, including separator | 8 KiB   |
| One body                    | 1 MiB   |
| JSON nesting                | 128     |
| Root fields per body        | 64      |

Inspection stops at an ambiguous or malformed header without guessing a later synchronization point. A malformed JSON
body with a known byte length remains a returned frame, so later complete frames can still be examined. A limit never
becomes an absent ID or a completed observation. Invalid export/configuration arguments raise the fixed
INVALID_WIRE_EVIDENCE error; raw input is not included in that error.

The helper checks ASCII framing, supported UTF-8 decoding and bounded JSON/root-member structure. It does not replace
LSP4J, certify all MIME or LSP schema rules, or prove that a decoded SDK result matched the original envelope. The
framing contract follows the
[LSP 3.17 base protocol](https://github.com/microsoft/language-server-protocol/blob/gh-pages/_specifications/lsp/3.17/specification.md#base-protocol)
and preserves the members described by [JSON-RPC 2.0](https://www.jsonrpc.org/specification).

## Checking the helper

Run the focused controls without a JVM build:

    node --test tools/perf/wire-evidence.test.mjs

The complete existing Node evidence-tool suite is:

    node --test tools/perf/*.test.mjs

CI already discovers the helper's tests through that wildcard. These checks cover the inspector; actual stream capture,
SDK decoding, real stdio artifacts and packaged editor consumers require their own integration checks.

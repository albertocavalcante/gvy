# Logging fixture and pipe baseline

This is L00a: reproducible synthetic inputs and a pipe counter. It does not complete the logging baseline or measure the
language server. Run from the repository root with Node 24 or newer:

```sh
node --test tools/logging/fixtures.test.mjs
node tools/logging/measure.mjs
```

`sourceFixture(kind, size)` in `fixtures.mjs` returns deterministic Groovy or Spock source (`groovy` / `spock`, `small`
/ `large`, 10 / 1,000 methods). `childFixture(mode)` returns exact bytes for `burst` (1,000 newline-terminated records),
`no-newline` (128 KiB, no terminator) and `unicode` (UTF-8, CRLF and LF). Run `node tools/logging/fixtures.mjs <mode>`
to emit one fixture on stdout. The producer splits its writes every seven bytes, including within UTF-8 code points; the
OS and runtime can coalesce or split reads. Stderr contains only the producer's JSON write-call count. Do not feed it
into a protocol stream.

The counter hashes and scans received chunks without keeping the complete output or record; a final unterminated record
counts once. It reports UTF-8 bytes, records, producer write calls and observed readable chunks separately. Node's
stream fixture does not call a flush API, so `flushCalls` is null, not zero. Readable chunks, writes and
operating-system syscalls are different quantities; chunk counts vary by run. The fixture itself creates its fixed-size
bytes in the child; this tool does not demonstrate that a production consumer has bounded retention.

## Frozen inputs

| Fixture      | UTF-8 bytes | SHA-256                                                            |
| ------------ | ----------: | ------------------------------------------------------------------ |
| groovy/small |         277 | `3563c860082dde801d0f938ba763fd1c1321e555663e683687bb86fa0ee97daf` |
| groovy/large |      29,797 | `91c7d96824e0ef64789e1bee9b7877bf034d2739e0c51a5ca413b7d80b1ab927` |
| spock/small  |         467 | `ea9f6a266b7fd4219a1bd31c6899e64a18ba723f2c849c1b2607159b6b80eb41` |
| spock/large  |      44,749 | `29878ff3cced37be7329a698eedc6f533dd49d5d9dcbf9dbc235c59000b5c1fd` |
| burst        |      10,890 | `d160b3b75541907fa8842174d32c7993bc384535efa7381ba24f0f4148c641b6` |
| no-newline   |     131,072 | `15601535eca4a38b7e31ad6494861121cb9f84ccf55d4beb6a707d4f7a87813d` |
| unicode      |          22 | `462571792adecdf5ee8a4a5081c4323deb9d57400f28b2981533529c0f0ca2c1` |

Hashes are tested. Intentionally changing a fixture requires updating the hash and preserving prior data for meaningful
comparisons. These are generated source strings, not a checked Spock dependency or a representative production corpus.

## Initial observation and limits

On the L00a base `ce45ade0bd665ad1970d74482f8ed28aec1c628d`, macOS arm64 and Node v26.13.0, a single synthetic run
reported 1,000 / 1 / 2 records and 10,890 / 131,072 / 22 bytes for burst / no-newline / unicode. Producer write calls
were 1,556 / 18,725 / 4; observed readable chunks were 2 / 192 / 2. This was one run with no warmup or timing, not a
production benchmark. No server, editor, JAR, logger configuration or request workload was involved. Separately, the
planning audit observed 15,420 DEBUG lines per Native visualizer probe; its different fixtures and runtime mean that
number is not comparable to this pipe probe. The delivery plan records that provenance.

The delivery contract proposes **64 KiB per record**, **256 KiB retained child tail**, **256 KiB or 512 events for the
support ring**, and **64 KiB per local export**. These are candidate capacities, not measured latency or production
budgets. The 128 KiB newline-free case exceeds the candidate record cap at EOF; it does not demonstrate recovery or
justify that cap by itself. Consumers must count UTF-8 bytes, explicitly mark truncation, continue draining input and
recover at the next complete record. Each owner must test its actual retained-memory limit and overflow behavior.
Support capture must allowlist fields before ingestion; a byte cap does not make raw data safe. Local bounded failure
detail has a separate best-effort redaction contract and may remain sensitive.

Still required for full L00: actual application logger record/byte/write/flush counts, explicit logging configurations,
startup and warmed typing/hover/completion/diagnostics with candidate and JAR hashes, allocation/retained-memory and
shutdown evidence, editor presentation and request p50/p95. No result here establishes those measurements, a budget, or
an improvement. Keep private raw output outside Git. This receipt is separate from the profiling evidence schema; any
integration with that schema requires a reviewed adapter.

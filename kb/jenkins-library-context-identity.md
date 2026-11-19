<!--
SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
SPDX-License-Identifier: Apache-2.0
-->

# Shared-library input identity and publication

Two Jenkinsfiles may request the same library alias while selecting different bytes or using different authorization
scopes. A branch name, cache path or Git HEAD cannot identify that context. Late work must also be unable to replace a
newer result after a document, profile or authorization change.

The internal `jenkins.library` types establish a disconnected, data-only foundation for
[#1954](https://github.com/albertocavalcante/gvy/issues/1954). They do not enable new library settings, acquisition,
completion or compiler inputs. Legacy activation and the source-key guard from
[#2034](https://github.com/albertocavalcante/gvy/pull/2034) retain their existing behavior.

## Identity layers

| Identity                    | Includes                                                                                                                                                     | Does not establish                                                 |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------ |
| Captured source fingerprint | Sorted relative entry names and exact owned bytes                                                                                                            | Filesystem capture consistency, Git freshness or executable safety |
| Input fingerprint           | Ordered selected libraries and dependency entries, aliases, resolver/revision labels, content fingerprints and closure status                                | Dependency discovery, per-class origins or verified completeness   |
| Context identity            | Input fingerprint plus workspace/document, document generation, profile/config generation, authorization scope/epoch and analysis engine/target/index schema | Permission to read, publish, navigate or compile                   |

`LibrarySourceSnapshot` copies input arrays and returns a copy for each read. Its entry names are normalized relative
logical paths; it never opens them. Callers must provide stable bytes during construction. This is not an atomic
filesystem snapshot or a directory traversal API.

Fingerprints use SHA-256 with distinct versioned format labels, length-prefixed UTF-16 code-unit strings, explicit null
markers, collection counts and length-prefixed bytes. File order is canonicalized; selected-library and dependency order
and duplicates are retained. Reordering dependency precedence therefore changes identity. This private format is not a
persisted cache or public protocol contract.

Resolution labels and content fingerprints are assertions supplied by a trusted producer, which must bind them to the
admitted bytes. `main` mapped to a JAR still says nothing about Git freshness. `UNKNOWN`, `PARTIAL` and `COMPLETE`
describe the caller's bounded declared dependency closure; the model does not verify those claims or infer
reflective/optional dependencies. Analysis identifiers must include relevant versions, options and compiler policy.
Identity values are not credentials or authorization decisions.

## Publication requires the current attempt

The following flow describes the primitive, not an integrated LSP guarantee:

```mermaid
sequenceDiagram
    participant Owner as Document/profile owner
    participant Fence as Publication fence
    participant Old as Older worker
    participant New as Current worker
    Owner->>Fence: begin(request A)
    Fence-->>Old: attempt A
    Owner->>Fence: begin(request B)
    Fence-->>New: attempt B
    New->>Fence: publish(B, immutable inputs)
    Fence-->>New: accepted
    Old->>Fence: publish(A, older inputs)
    Fence-->>Old: rejected; current result retained
```

Each fence belongs to one workspace/document pair. `begin` creates a unique attempt even if every request field equals a
previous request. It clears the earlier result while work is pending; there is no implicit stale fallback. Publication
succeeds once for the current attempt. Canceling that attempt also removes its published result. Canceling an old or
foreign attempt leaves the current result intact.

State transitions and reads are synchronized. No caller callback executes under the fence's lock. `publishedFor` selects
the recorded result by full request; `current` additionally checks its input fingerprint for delayed consumers. Neither
method observes external byte or authorization changes. The producer must invalidate changed input and authorization
under the owning synchronization before delivery. There is no documentless accessor or workspace-wide catalogue
enumeration. A returned immutable snapshot can still be retained by a caller. This primitive cannot revoke bytes or
results already delivered to that caller.

## Integration gates remain open

| Owner scope                               | Next action and acceptance gate                                                                                                                          |
| ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Configuration/resolution, #1954           | Add exclusive typed descriptors, catalogue/request/materialization/capability models and conflict diagnostics without changing legacy selection          |
| Local capture, #1954                      | Bound traversal and resource use; snapshot dirty/untracked bytes coherently; detect edits before publication even with unchanged HEAD                    |
| Context lifecycle, #1954/#1958            | Bind the fence to real document/config/auth transitions, cancellation and immutable payloads; recheck exact snapshot identity for delayed resolve/output |
| Source navigation, #1935/#1954            | Reuse mediated handles and enforce current authorization on open; prove leases/lifetimes and prevent raw cache-path fallback                             |
| Compiler and LSP integration, #1954/#1958 | Prove safe positive controls and all affected engines/endpoints before admitting any new source inputs; exclude protected API from unscoped output       |
| Loading and adapters, #1955–#1959         | Keep compile/runtime views distinct; prove Jenkins behavior and adapter safety before enabling sources                                                   |

There is no new global index, compiler/classloader, filesystem, network, source-provider or editor integration here.
Compile/runtime availability, absent/unknown capabilities, dependency graphs and parent-load contexts, per-class origins
and protected source handles need their own models and executable gates. This slice fingerprints a flat ordered input
manifest. The full [parent design](https://github.com/albertocavalcante/gvy/issues/1953) remains the authority for those
requirements.

## Verification scope

Focused unit tests cover captured-byte ownership, path validation, unambiguous content encoding, ordered input identity,
context separation, replacement, cancellation, equal-request ABA and delayed publication. Existing Jenkins tests cover
the compatibility boundary. These are pure-contract tests; they do not prove actual LSP isolation, Jenkins conformance,
filesystem coherence, source access revocation or safe compiler admission.

Run the focused tests through the repository's normal Gradle environment and host admission gate:

```sh
build-slot -- ./gradlew :jenkins:test --tests '*LibraryInputSnapshotTest' --tests '*LibraryContextPublicationTest'
```

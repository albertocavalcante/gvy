# Network policy contract

Status: preparatory foundation for [#1960](https://github.com/albertocavalcante/gvy/issues/1960) and
[#1963](https://github.com/albertocavalcante/gvy/issues/1963). Production adapters and packaged qualification remain
pending. This foundation does not establish a restricted-mode guarantee.

## Authority and parsing

The trusted input object has two optional fields: `mode` (`cache-only` or `connected`) and `allowedOrigins` (an array of
exact HTTP origins). Absence means `cache-only` with no approved origins. Unknown fields, incorrect types, unknown modes
and any invalid origin invalidate the entire snapshot. Invalid snapshots deny acquisition with `INVALID_CONFIGURATION`.
Policy parsing is independent of ordinary workspace settings parsing.

Editor authority comes from user or organization settings. Workspace and folder settings cannot broaden it. The editor
foundation reads only the inspected global values. Installation receipts convey installation facts, never permission.
Standalone tools will accept only an explicit absolute `--network-policy` JSON path; absence denies acquisition. They
must not discover policy in the checkout or a receipt. Standalone file loading remains adapter work.

Connected mode still needs explicit origin approval. An internal-network profile is connected with internal origins; it
adds no repositories or credentials. There are no automatic public or loopback exceptions. Approvals in cache-only mode
do not enable acquisition.

## Exact origins

Matching uses the lowercased scheme and ASCII hostname plus normalized numeric port. HTTP port 80 and HTTPS port 443 are
omitted from the canonical origin. An origin can have a final `/`, but no other path, query, fragment or userinfo.
Requests can have a path/query. Wildcards, whitespace, backslashes, percent-encoded authorities, trailing-dot hosts,
noncanonical IPv4 and invalid ports are rejected. International names must use ASCII A-labels. IPv6 literals are
currently unavailable; invalid authorities deny acquisition rather than falling through to another transport.

Request failures are structured codes: `INVALID_CONFIGURATION`, `CACHE_ONLY`, `INVALID_URL` and `ORIGIN_NOT_ALLOWED`, in
that precedence. A rejected revision/registration reports `STALE_POLICY`. Failure values must not include
credential-bearing URLs. An adapter must treat denial as terminal.

## Lifetime

Snapshots defensively copy approvals and expose immutable authority with a monotonically increasing safe-integer
revision. Policy revisions are independent of dependency generations. Controllers register cancellation before an
operation starts; replacement publishes the new revision and invalidates registrations before invoking cancellation
callbacks. Callback failure must not strand other operations. Closing a registration is idempotent.

Publication runs through the registration's short synchronous `publish` action. A separate `isCurrent`/`check` query is
diagnostic and cannot make a later publication atomic. Kotlin serializes publication and replacement under the same
lock; Node uses synchronous event-loop execution. Never use these publication actions for blocking work or asynchronous
writes. Cancellation requests do not acknowledge transport completion or release a Gradle connection lease; scoped lease
acknowledgement is separate work under #1589.

## Conformance and remaining gates

The canonical executable examples are
[`conformance.json`](../build-tool/src/test/resources/network-policy/conformance.json). Kotlin, Node and the typed
editor API run the same fixture. The dependency-free Node suite runs with
`node --test editors/code/tools/tests/networkPolicy.test.js`; Gradle tests and editor type/lint/unit checks supply the
other implementation gates.

Subsequent adapters must select provisioned Gradle with `useInstallation`, preserve the effective home/init scripts,
repository order, credentials and `mavenLocal()`, apply offline arguments consistently, authorize every redirect before
its request, preserve transfer limits/deadlines, bind credentials to their origin and reject TLS downgrades. Policy
changes must cancel supported operations before restart prompts and fence late results/artifacts. Opaque Aether/CI
acquisition remains unavailable until qualified. The packaged non-root Linux matrix with external egress denied and
loopback retained must pass before default cutover and migration claims.

gvy's origin rules govern its own clients. Gradle plugins and tasks execute arbitrary code; their traffic requires
external enforcement. Gradle offline mode controls dependency resolution and is not a process sandbox.

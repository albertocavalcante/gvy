# License metadata and source headers

gvy uses [SPDX License List](https://spdx.org/licenses/) identifiers to associate licensing information with each file.
Original project source uses `Apache-2.0`. Imported material retains its own notices, holders and license.

## Run the checks

Install Python 3.11 or newer, then bootstrap the pinned tooling from the repository root:

```sh
python -m pip install --require-hashes --only-binary=:all: -r .reuse/bootstrap-requirements.txt
uv sync --locked --project .reuse
```

After setup, validation works offline:

```sh
make license-test
make license-check
```

The portable equivalent of `make license-check` is:

```sh
uv run --offline --locked --no-dev --project .reuse python .reuse/check.py --root .
```

The pre-commit hook checks the entire working tree. CI checks the committed publication tree; partial staging does not
change the hook's scope.

Gradle's root `licenseCheck` task runs the same check. Root and module verification and publication tasks depend on that
shared task, which runs once per invocation. Focused test or compilation tasks can still run independently. Repository
licensing validation applies independently of the build system: run the same check when working with Bazel or Buck2. CI
runs it regardless of build-path filters.

The checker combines REUSE's file coverage and SPDX expression validation with gvy's leading-comment policy. It also
checks the reviewed SHA-256 inventory in `.reuse/license-hashes.toml`. Source strings or external metadata cannot
satisfy a maintained source file's physical-header requirement.

## Add or change a file

Maintained source, test implementation, build scripts and workflows use a leading comment in their language:

```kotlin
// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
```

Preserve existing holders and notices. Use an appropriate attribution for new work; the collective wording above
identifies original gvy material when no more specific holder is recorded. Existing notices do not need annual year
updates.

Keep script shebangs first and Python encoding declarations within the first two lines. Keep Kotlin file annotations and
other required prologues valid. Bulk annotation requires inspecting the resulting diff: an annotation tool can move a
Python encoding declaration and make otherwise valid source unreadable.

Use external metadata for fixture payloads, snapshots, generated data, documents and formats such as JSON. The
`REUSE.toml` files list exact paths so a new file needs an explicit licensing decision. A neighboring `.license` file is
useful for imported or binary material. The source classification in `.reuse/check.py` remains authoritative for files
that require inline comments.

Do not modify fixture contents, expected positions or golden bytes to satisfy a licensing check. Test source strings
that illustrate copyright or SPDX declarations can use `REUSE-IgnoreStart` and `REUSE-IgnoreEnd` comments around the
example; keep the actual file header outside that block.

## Imported and generated material

| Material                                                  | Attribution and treatment                                                                                       |
| --------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| Gradle launchers and wrapper JAR                          | Retain upstream bytes and notices; adjacent `.license` files record the Gradle authors and Apache-2.0.          |
| `third_party/patches/*.patch`                             | Apache-2.0 metadata records upstream Bazel authors and project modifications.                                   |
| `editors/code/patches/@vscode__test-electron@3.1.0.patch` | MIT metadata credits Microsoft and the gvy modifications; the SDK's original runtime source remains attributed. |
| `third_party/jvm/BUCK`                                    | Keep generator-owned contents; associate licensing with the exact path in `REUSE.toml`.                         |
| Groovy logo                                               | Use the ASF-published asset and its adjacent provenance record.                                                 |
| Editor grammar                                            | Preserve the original project grammar and its Apache-2.0 metadata.                                              |
| SCIP protocol schema                                      | Credit the upstream SCIP contributors, retain Apache-2.0, and preserve the imported schema body.                |

The SDK patch derives from the pinned `@vscode/test-electron` 3.1.0 runtime and preserves its
[Microsoft MIT license](https://github.com/microsoft/vscode-test/blob/2a4b25c3a48411bdd001af1cae136a308a0c7e32/LICENSE).
Its neighboring `.license` file also credits the project modifications. The approved MIT text remains in the license
inventory; the patch does not relabel the dependency as project-owned Apache code.

The [SCIP schema](https://github.com/scip-code/scip/blob/4a9a63dc95cd0e35f3bb4d7b43d5d65f747dada8/scip.proto) and its
[upstream license](https://github.com/scip-code/scip/blob/4a9a63dc95cd0e35f3bb4d7b43d5d65f747dada8/LICENSE) establish
the imported protocol's provenance and Apache-2.0 licensing. The local schema body differs from that version only by the
existing documentation correction `preferrable` to `preferable`. Its header credits the upstream contributor collective;
it does not infer a year or company from the upstream license's unfilled copyright template.

The
[Groovy logo source](https://github.com/apache/groovy-website/blob/ab35872f2b17751960bd3162ce9516bfc10bdb3d/site/src/site/assets/img/groovy-logo-colored.svg)
and
[rules_jvm_external source license](https://github.com/bazel-contrib/rules_jvm_external/blob/f23697ea7098330461558bedd742b8131acc7f88/LICENSE)
provide the upstream references. Adding differently licensed source requires reviewing its provenance and updating the
precise path policy and required license texts. Do not relabel imported work as project-owned Apache code.

## Packages and source exports

The server JAR retains the project license and namespaces dependency legal files by archive filename and SHA-256. Its
verification tasks check collision handling, unsafe paths and the actual shaded archive's contents. The editor copies
both Codicons license files with the font and CSS, including attribution. Parser artifacts retain the project license.

Source-export metadata is rooted in each exported tree. Copybara must carry the required `LICENSES/` texts and wrapper
sidecars with it; metadata must not refer outside the destination. Validate a fresh exported tree before building it.
REUSE's ignored-file behavior differs once a source archive has no Git metadata, so a generated build tree is not a
substitute for that source-export check.

Forward Copybara jobs check the checkout, then generate a private configuration whose origin is pinned to that exact
commit. The binding helper rejects a dirty checkout or mismatched revision. The reverse CodeNarc import retains its
standalone repository origin; its resulting pull request receives the normal repository checks.

These checks validate declared licensing and preserve notices. Dependency-license compatibility analysis and a full
software bill of materials are separate work.

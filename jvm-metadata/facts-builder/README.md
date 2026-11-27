<!--
SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
SPDX-License-Identifier: Apache-2.0
-->

# facts-builder

Reads class files with ASM into the model of [`:jvm-metadata:facts`](../facts/FORMAT.md). This first part handles one
class file and the path it was found at; reading jars and the JDK image comes next.

The builder needs an ASM 9.x that checks an attribute length against the class file, as ASM 9.9.1 does. A test in each
build (Gradle and Bazel) fails if the ASM in use does not. The newest class-file version it reads is the newest one the
ASM in use reads, which the builder finds by probing ASM.

## Differences from the class-name index

The class-name index in `gls` (`ClasspathIndex`) and this builder read the same archive paths, but not with the same
rules. A consumer that replaces the index with facts has to account for these differences.

| Input                                                             | Class-name index                                                                           | Builder                                               |
| ----------------------------------------------------------------- | ------------------------------------------------------------------------------------------ | ----------------------------------------------------- |
| Versioned copy for a release above the running JDK                | Drops it                                                                                   | Keeps it, with its release, for any release up to 255 |
| Version directory `0`, `09` or `+9`                               | Accepts it, as the number `toIntOrNull` reads                                              | Skips it and counts `UNSUPPORTED_RELEASE`             |
| Non-numeric or empty version directory                            | Ignores the path                                                                           | Skips it and counts `UNSUPPORTED_RELEASE`             |
| Path that is not a valid internal name (a dot, a bracket, a `//`) | Reads the class, then rejects it on a name mismatch unless the class names itself that way | Skips it and counts `INVALID_NAME` before reading     |
| Internal name over 65,535 bytes, or with an unpaired surrogate    | Rejects it as a name mismatch                                                              | Skips it and counts `INVALID_NAME`                    |

`module-info.class` and `package-info.class` are ignored by both, at any depth.

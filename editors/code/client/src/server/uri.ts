// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

/** Converts VS Code's encoded Windows drive separator for Java's file URI handling. */
export function toProtocolUri(
  uri: string,
  platform: NodeJS.Platform = process.platform,
): string {
  // Only a file URI's drive separator needs decoding. Encoded colons elsewhere
  // (including UNC paths, queries and fragments) must retain their meaning.
  return platform === "win32"
    ? uri.replace(/^(file:\/\/\/[a-z])%3a(?=\/)/gi, "$1:")
    : uri;
}

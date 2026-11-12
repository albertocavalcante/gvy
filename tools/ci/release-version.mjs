// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Match the SemVer subset supported by release tags and VSCE, without dependencies.
export function isReleaseVersion(version) {
  if (typeof version !== 'string' || version.length > 256) return false;
  const separator = version.indexOf('-');
  const core = separator < 0 ? version : version.slice(0, separator);
  const numbers = core.split('.');
  if (numbers.length !== 3 || numbers.some(number =>
    !/^(0|[1-9][0-9]*)$/.test(number) || !Number.isSafeInteger(Number(number)))) return false;
  if (separator < 0) return true;
  return version.slice(separator + 1).split('.').every(identifier =>
    /^[0-9A-Za-z-]+$/.test(identifier) && !/^0[0-9]+$/.test(identifier));
}

export function releaseVersionFromTag(tag) {
  const prefix = ['vscode-groovy-v', 'v'].find(candidate => typeof tag === 'string' && tag.startsWith(candidate));
  const version = prefix ? tag.slice(prefix.length) : '';
  if (!isReleaseVersion(version)) {
    throw new Error(`Invalid release tag: ${tag}. Expected vX.Y.Z or vscode-groovy-vX.Y.Z with an optional SemVer prerelease`);
  }
  return version;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    if (process.argv.length !== 3) throw new Error('Usage: release-version.mjs <release-tag>');
    releaseVersionFromTag(process.argv[2]);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}

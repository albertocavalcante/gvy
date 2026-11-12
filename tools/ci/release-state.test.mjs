// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { isReleaseVersion } from './release-version.mjs';

const repository = new URL('../../', import.meta.url);
const read = path => readFileSync(new URL(path, repository), 'utf8');

test('the unified release has a present simple version file and matching package versions', () => {
  const config = JSON.parse(read('tools/release-please/config.json')).packages['.'];
  const version = JSON.parse(read('tools/release-please/manifest.json'))['.'];
  assert.equal(config['release-type'], 'simple');
  assert.ok(isReleaseVersion(version), 'manifest must contain a valid release version');

  // The simple strategy does not create this file; its updater writes only the version and newline.
  assert.equal(read(config['version-file'] ?? 'version.txt'), `${version}\n`);

  for (const path of ['build.gradle.kts', 'editors/code/package.json']) {
    assert.ok(config['extra-files'].includes(path), `${path} must be updated by the unified release`);
  }
  const gradleVersion = read('build.gradle.kts').match(/^\s*val baseVersion\s*=\s*"([^"]+)"/m)?.[1];
  assert.equal(gradleVersion, version, 'Gradle base version must match the release manifest');
  assert.equal(JSON.parse(read('editors/code/package.json')).version, version,
    'extension version must match the release manifest');
});

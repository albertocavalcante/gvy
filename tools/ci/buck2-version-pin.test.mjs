// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const root = new URL('../../', import.meta.url);
const read = (path) => readFileSync(new URL(path, root), 'utf8');

// .buckversion holds comment lines and exactly one version line.
function pinnedVersion(text) {
  const versions = text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line !== '' && !line.startsWith('#'));
  assert.equal(versions.length, 1, '.buckversion must hold exactly one version line');
  return versions[0];
}

// The workflow installs Buck2 from the release named by its BUCK2_VERSION environment value.
function workflowVersion(workflow) {
  const matches = [...workflow.matchAll(/^\s*BUCK2_VERSION:\s*["']?([^"'\s#]+)["']?\s*(?:#.*)?$/gm)];
  assert.equal(matches.length, 1, 'ci-buck2.yml must set BUCK2_VERSION exactly once');
  return matches[0][1];
}

test('.buckversion and the Buck2 workflow pin the same release', () => {
  const pinned = pinnedVersion(read('.buckversion'));
  assert.match(pinned, /^\d{4}-\d{2}-\d{2}$/, 'Buck2 releases are named by date');
  assert.equal(
    workflowVersion(read('.github/workflows/ci-buck2.yml')),
    pinned,
    'update .buckversion and BUCK2_VERSION (with its checksums) in .github/workflows/ci-buck2.yml together',
  );
});

test('the version readers reject ambiguous input', () => {
  assert.equal(pinnedVersion('# comment\n\n2026-01-19\n'), '2026-01-19');
  assert.equal(pinnedVersion('# comment\r\n2026-01-19\r\n'), '2026-01-19');
  assert.throws(() => pinnedVersion('# only a comment\n'));
  assert.throws(() => pinnedVersion('2026-01-19\n2026-02-02\n'));
  assert.equal(workflowVersion('env:\n  BUCK2_VERSION: "2026-01-19"\n'), '2026-01-19');
  assert.equal(workflowVersion('env:\n  BUCK2_VERSION: 2026-01-19 # pinned\n'), '2026-01-19');
  assert.throws(() => workflowVersion('env:\n  OTHER: "x"\n'));
  assert.throws(() => workflowVersion('env:\n  BUCK2_VERSION: "a"\njobs:\n  x:\n    env:\n      BUCK2_VERSION: "b"\n'));
});

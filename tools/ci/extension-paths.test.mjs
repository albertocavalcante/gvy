// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { posix } from 'node:path';
import test from 'node:test';

const source = readFileSync(new URL('../../.github/workflows/extension.yml', import.meta.url), 'utf8');
const readme = 'tests/e2e/README.md';

// Inspect the actual workflow's explicit block lists; unsupported syntax must require deliberate review.
function ignoresFor(event) {
  const lines = source.split('\n');
  const start = lines.indexOf(`  ${event}:`);
  assert.notEqual(start, -1, `missing ${event} event`);
  const block = [];
  for (const line of lines.slice(start + 1)) {
    if (/^\S|^  \S/.test(line)) break;
    block.push(line);
  }
  const at = block.indexOf('    paths-ignore:');
  assert.notEqual(at, -1, `missing ${event} paths-ignore`);
  const patterns = [];
  for (const line of block.slice(at + 1)) {
    if (!line.trim() || line.trimStart().startsWith('#')) continue;
    if (!line.startsWith('      - ')) break;
    const match = line.match(/^      - '([^']+)'$/);
    assert.ok(match, `unexpected ${event} filter scalar: ${line}`);
    patterns.push(match[1]);
  }
  assert.ok(patterns.length, `empty ${event} ignore list`);
  return patterns;
}

// The reviewed filters use only literals, * and **; no general GitHub-expression evaluator is claimed.
function ignored(patterns, path) {
  return patterns.some(pattern => posix.matchesGlob(path, pattern));
}

for (const event of ['push', 'pull_request']) {
  test(`${event}: existing narrative exclusions remain`, () => {
    const patterns = ignoresFor(event);
    for (const path of ['README.md', 'docs/installation.md', 'kb/testing/plan.md', 'editors/zed/README.md']) {
      assert.ok(ignored(patterns, path), path);
    }
  });

  test(`${event}: the harness guide avoids the extension server build`, () => {
    assert.ok(ignored(ignoresFor(event), readme));
  });

  test(`${event}: executable fixtures and source still trigger extension validation`, () => {
    const patterns = ignoresFor(event);
    for (const path of [
      'tests/e2e/resources/scenarios/initialize-basic.yaml',
      'tests/e2e/resources/snapshots/hover/println.md',
      'tests/e2e/resources/workspaces/project/README.md',
      'tests/lsp-client/resources/frame.md',
      'tests/e2e/kotlin/example/ScenarioTest.kt',
      'tests/e2e/README.md.groovy',
      'gls/src/main/kotlin/example/Server.kt',
      'editors/code/client/src/extension.ts',
      '.github/workflows/extension.yml',
    ]) {
      assert.equal(ignored(patterns, path), false, path);
    }
  });

  test(`${event}: a guide plus an executable input still triggers validation`, () => {
    const patterns = ignoresFor(event);
    const changed = [readme, 'tests/e2e/resources/snapshots/hover/println.md'];
    assert.ok(changed.some(path => !ignored(patterns, path)));
  });

  test(`${event}: the new exclusion is the one explicit guide path`, () => {
    assert.deepEqual(ignoresFor(event), ['*.md', 'docs/**', 'kb/**', 'editors/zed/**', readme]);
  });
}

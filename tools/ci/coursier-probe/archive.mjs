// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtemp, open, rename, rm, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { base, fileHash, hash } from './io.mjs';

const repo = 'albertocavalcante/gvy';
const api = path => JSON.parse(execFileSync('gh', ['api', 'repos/' + repo + path], { maxBuffer: 4_000_000 }));
const git = (...args) => execFileSync('git', args, { encoding: 'utf8' }).trim();

export function validateRun(record, head, id, attempt) {
  assert.match(head, /^[a-f0-9]{40}$/);
  assert.match(id, /^[1-9]\d*$/);
  assert.match(attempt, /^[1-9]\d*$/);
  assert.equal(String(record.id), id);
  assert.equal(String(record.run_attempt), attempt);
  assert.equal(record.repository.full_name, repo);
  assert.equal(record.head_repository.full_name, repo);
  assert.equal(record.head_sha, head);
  assert.equal(record.path, '.github/workflows/gradle-compat.yml');
  assert.equal(record.event, 'workflow_dispatch');
  assert.equal(record.status, 'completed');
}

export function runContext(id, attempt) {
  assert.match(id, /^[1-9]\d*$/);
  assert.match(attempt, /^[1-9]\d*$/);
  const head = git('rev-parse', 'HEAD');
  assert.equal(git('status', '--porcelain', '--untracked-files=no'), '', 'review checkout must be clean');
  const diff = git('diff', '--name-only', base, head).split('\n');
  assert(diff.every(path => path === '.github/workflows/gradle-compat.yml' || path.startsWith('tools/ci/coursier-probe/')));
  const record = api('/actions/runs/' + id + '/attempts/' + attempt);
  validateRun(record, head, id, attempt);
  const listing = api('/actions/runs/' + id + '/artifacts?per_page=100');
  assert.equal(listing.artifacts.length, listing.total_count, 'partial GitHub artifact inventory');
  return { head, tree: git('rev-parse', 'HEAD^{tree}'), run: id, attempt, conclusion: record.conclusion, artifacts: listing.artifacts };
}

function unzip(zip, ...args) {
  return execFileSync('unzip', [...args.slice(0, 1), zip, ...args.slice(1)], { maxBuffer: 128 * 1024 * 1024 });
}

// Anchor the downloaded bytes to GitHub's archive digest. Read named members
// into memory. No ZIP entry is extracted to a filesystem path.
export async function loadArchive(root, ctx, name) {
  const matches = ctx.artifacts.filter(a => a.name === name);
  assert.equal(matches.length, 1, 'missing or duplicated GitHub artifact ' + name);
  const artifact = matches[0];
  assert.equal(artifact.expired, false);
  assert.equal(String(artifact.workflow_run.id), ctx.run);
  assert.equal(artifact.workflow_run.head_sha, ctx.head);
  assert.match(artifact.digest, /^sha256:[0-9a-f]{64}$/);
  const zip = join(root, name + '.zip');
  try {
    assert((await stat(zip)).isFile());
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    const tmp = await mkdtemp(join(root, '.download-'));
    try {
      const target = join(tmp, 'download.zip');
      const output = await open(target, 'wx', 0o600);
      try {
        execFileSync('gh', ['api', 'repos/' + repo + '/actions/artifacts/' + artifact.id + '/zip'],
          { stdio: ['ignore', output.fd, 'pipe'], timeout: 180_000 });
      } finally {
        await output.close();
      }
      assert.equal('sha256:' + await fileHash(target), artifact.digest);
      await rename(target, zip);
    } finally {
      await rm(tmp, { recursive: true, force: true });
    }
  }
  assert.equal('sha256:' + await fileHash(zip), artifact.digest);
  const entries = unzip(zip, '-Z1').toString().trimEnd().split('\n').filter(p => !p.endsWith('/')).sort();
  assert(entries.length <= 160 && entries.includes('manifest.json'));
  const manifest = JSON.parse(unzip(zip, '-p', 'manifest.json'));
  assert.deepEqual(manifest.map(row => row.path).sort(), entries.filter(p => p !== 'manifest.json'));
  const files = new Map();
  for (const row of manifest) {
    assert.match(row.path, /^(?:[A-Za-z0-9_-]+\/)*[A-Za-z0-9_.-]+$/);
    assert(!row.path.split('/').some(p => p === '.' || p === '..') && !files.has(row.path));
    assert.match(row.sha256, /^[a-f0-9]{64}$/);
    const bytes = unzip(zip, '-p', row.path);
    assert.equal(bytes.length, row.bytes);
    assert.equal(hash(bytes), row.sha256);
    files.set(row.path, bytes.toString('utf8'));
  }
  return { files, id: artifact.id, digest: artifact.digest };
}

// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { expect, test } from 'bun:test';
import { staleOwned } from './owned.mjs';

test('only returns old owned generated paths, rejecting traversal', () => {
  expect(staleOwned(['guides/old.md', 'guides/current.md'], ['guides/current.md'])).toEqual(['guides/old.md']);
  expect(() => staleOwned(['../outside.md'], [])).toThrow('unsafe');
});

test('discovers marked stale pages without a manifest and preserves unowned files', async () => {
  const { mkdtemp, mkdir, writeFile, rm } = await import('node:fs/promises');
  const { tmpdir } = await import('node:os');
  const path = await import('node:path');
  const { discoverOwned, marker, removeStaleOwned } = await import('./owned.mjs');
  const dir = await mkdtemp(path.join(tmpdir(), 'gvy-owned-'));
  try {
    await mkdir(path.join(dir, 'guides'));
    await writeFile(path.join(dir, 'guides/old.md'), `old\n${marker}\n`);
    await writeFile(path.join(dir, 'guides/manual.md'), 'manual');
    expect(await discoverOwned(dir)).toEqual(['guides/old.md']);
    await removeStaleOwned(dir, [], []);
    const { readFile } = await import('node:fs/promises');
    expect(await discoverOwned(dir)).toEqual([]);
    expect(await readFile(path.join(dir, 'guides/manual.md'), 'utf8')).toBe('manual');
    expect(await Bun.file(path.join(dir, 'guides/old.md')).exists()).toBe(false);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('preflight refuses unowned and symlink destinations before changing anything', async () => {
  const { mkdtemp, mkdir, writeFile, readFile, symlink, rm } = await import('node:fs/promises');
  const { tmpdir } = await import('node:os');
  const path = await import('node:path');
  const { preflight, marker } = await import('./owned.mjs');
  const dir = await mkdtemp(path.join(tmpdir(), 'gvy-preflight-'));
  try {
    await mkdir(path.join(dir, 'guides'));
    await writeFile(path.join(dir, 'guides/manual.md'), 'keep');
    await writeFile(path.join(dir, 'guides/owned.md'), `old\n${marker}\n`);
    await expect(preflight(dir, [['a.md', 'guides/manual', 'Manual']])).rejects.toThrow('unmarked');
    expect(await readFile(path.join(dir, 'guides/manual.md'), 'utf8')).toBe('keep');
    await symlink('guides', path.join(dir, 'linked'));
    await expect(preflight(dir, [['a.md', 'linked/new', 'Link']])).rejects.toThrow('symlink');
    await symlink('manual.md', path.join(dir, 'guides/link.md'));
    await expect(preflight(dir, [['a.md', 'guides/link', 'Link']])).rejects.toThrow('symlink');
    await preflight(dir, [['a.md', 'guides/owned', 'Owned'], ['b.md', 'guides/new', 'New']]);
    await writeFile(path.join(dir, 'guides/owned.md'), `new\n${marker}\n`);
    await writeFile(path.join(dir, 'guides/new.md'), `new\n${marker}\n`);
    expect(await readFile(path.join(dir, 'guides/owned.md'), 'utf8')).toStartWith('new');
    expect(await readFile(path.join(dir, 'guides/new.md'), 'utf8')).toStartWith('new');
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('preflight rejects duplicate sources, equivalent routes and unsafe paths', async () => {
  const { preflight } = await import('./owned.mjs');
  await expect(preflight('absent', [['a.md', 'one', 'A'], ['a.md', 'two', 'B']])).rejects.toThrow('duplicate source');
  await expect(preflight('absent', [['a.md', 'one', 'A'], ['b.md', 'one/index', 'B']])).rejects.toThrow('duplicate route');
  await expect(preflight('absent', [['a.md', '../escape', 'A']])).rejects.toThrow('unsafe');
  await expect(preflight('absent', [['a.md', 'index', 'A']])).rejects.toThrow('reserved');
});

test('ownership inspection closes its handle when reading fails', async () => {
  const { inspectOwned } = await import('./owned.mjs');
  let closed = false;
  const openFile = async () => ({
    stat: async () => ({ isFile: () => true }),
    readFile: async () => { throw new Error('read failure'); },
    close: async () => { closed = true; },
  });
  await expect(inspectOwned('unused', openFile)).rejects.toThrow('read failure');
  expect(closed).toBe(true);
});

test('writes only owned regular files and refuses a swapped symlink', async () => {
  const { mkdtemp, writeFile, readFile, symlink, rm } = await import('node:fs/promises');
  const { tmpdir } = await import('node:os');
  const path = await import('node:path');
  const { writeOwned, marker } = await import('./owned.mjs');
  const dir = await mkdtemp(path.join(tmpdir(), 'gvy-write-'));
  try {
    const target = path.join(dir, 'target.md');
    const victim = path.join(dir, 'victim.md');
    await writeFile(victim, 'victim');
    await symlink(path.join(dir, 'missing'), target);
    await expect(writeOwned(target, `new\n${marker}\n`)).rejects.toThrow();
    expect(await Bun.file(path.join(dir, 'missing')).exists()).toBe(false);
    await rm(target);
    await symlink(victim, target);
    await expect(writeOwned(target, `new\n${marker}\n`)).rejects.toThrow();
    expect(await readFile(victim, 'utf8')).toBe('victim');
    await rm(target);
    await writeOwned(target, `new\n${marker}\n`);
    await writeOwned(target, `updated\n${marker}\n`);
    expect(await readFile(target, 'utf8')).toBe(`updated\n${marker}\n`);
    await writeFile(target, 'manual');
    await expect(writeOwned(target, `new\n${marker}\n`)).rejects.toThrow('unmarked');
    expect(await readFile(target, 'utf8')).toBe('manual');
    const { readdir } = await import('node:fs/promises');
    expect((await readdir(dir)).filter(name => name.startsWith('.gvy-stage-'))).toEqual([]);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('cleans staging and preserves existing files when link fails', async () => {
  const { mkdtemp, readdir, readFile, writeFile, rm } = await import('node:fs/promises');
  const { tmpdir } = await import('node:os');
  const path = await import('node:path');
  const { marker, writeOwned } = await import('./owned.mjs');
  const dir = await mkdtemp(path.join(tmpdir(), 'gvy-link-failure-'));
  try {
    await writeFile(path.join(dir, 'victim.md'), 'preserve');
    await expect(writeOwned(path.join(dir, 'a'.repeat(300)), `${marker}\n`)).rejects.toMatchObject({ code: 'ENAMETOOLONG' });
    expect(await readdir(dir)).toEqual(['victim.md']);
    expect(await readFile(path.join(dir, 'victim.md'), 'utf8')).toBe('preserve');
  } finally { await rm(dir, { recursive: true, force: true }); }
});

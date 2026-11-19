// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { test, expect } from 'bun:test';
import { project } from './projection.mjs';

test('projects links, admonitions, title anchor and preserves code fences', async () => {
  const result = await project('# Hello world\n\n[Next](other.md#part)\n\n> [!NOTE]\n> Careful.\n\n```js\nconst x = "{hello}";\n```\n', {source:'docs/a.md', title:'Hello world', description:'Example', routes:{'docs/other.md':'/guides/other/'}});
  expect(result).toContain('id="hello-world"');
  expect(result).toContain('/guides/other/#part');
  expect(result).toContain('Note:');
  expect(result).toContain('const x = "{hello}";');
});

test('rejects unrecognized local markdown links', async () => {
  await expect(project('# Title\n\n[bad](missing.md)', {source:'docs/a.md', title:'Title', description:'Example', routes:{}})).rejects.toThrow('missing.md');
});

test('rejects HTML attributes and scripts instead of trusting anchor prefixes', async () => {
  for (const html of ['<span id="x" onclick="alert(1)">', '<script>alert(1)</script>', '<a id="x" onclick="alert(1)"></a>']) {
    await expect(project(`# Title\n\n${html}`, {source:'docs/a.md', title:'Title', description:'Example', routes:{}})).rejects.toThrow('unsupported HTML');
  }
});

test('rejects missing or repeated top-level titles', async () => {
  for (const markdown of ['No heading', '# First\n\n# Second']) {
    await expect(project(markdown, {source:'docs/a.md', title:'Title', description:'Example', routes:{}})).rejects.toThrow('title');
  }
});

test('retains source attribution and leaves explicit absolute site links alone', async () => {
  const output = await project('# Title\n\n[direct](/gvy/guides/quickstart/#example)', {source:'docs/a.md', title:'Title', description:'Example', routes:{}});
  expect(output).toContain('[direct](/gvy/guides/quickstart/#example)');
  expect(output).toContain('github.com/albertocavalcante/gvy/edit/main/docs/a.md');
  expect(output).toContain('github.com/albertocavalcante/gvy/blob/main/docs/a.md');
});

test('rejects a repeated title anchor that cannot be projected faithfully', async () => {
  await expect(project('# Repeated\n\n## Repeated', {source:'docs/a.md', title:'Title', description:'Example', routes:{}})).rejects.toThrow('duplicate title anchor');
});

test('reference attribution points to the owning manifest', async () => {
  const output = await project('# Settings', {source:'docs/user/reference-settings.md', editSource:'editors/code/package.json', title:'Settings', description:'Example', routes:{}});
  expect(output).toContain('editUrl: https://github.com/albertocavalcante/gvy/edit/main/editors/code/package.json');
});

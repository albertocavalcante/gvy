// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { expect, test } from 'bun:test';
import { validate } from './validate.mjs';

test('finds missing fragment and bad base while accepting valid asset', async () => {
  const files = new Map([['index.html', '<a href="/gvy/page/#lost">bad fragment</a><a href="/page/">bad base</a><img src="/gvy/icon.svg">'], ['page/index.html', '<h1 id="found">Page</h1>'], ['icon.svg', '<svg/>']]);
  const errors = validate(files, '/gvy/', {});
  expect(errors.join('\n')).toContain('#lost');
  expect(errors.join('\n')).toContain('/page/');
  expect(errors.join('\n')).not.toContain('icon.svg');
});

test('reports missing assets and required legacy fragments', () => {
  const files = new Map([['index.html', '<img src="/gvy/missing.svg"><h1 id="present">Home</h1>']]);
  const errors = validate(files, '/gvy/', {'/': ['old-anchor']});
  expect(errors.join('\n')).toContain('missing.svg');
  expect(errors.join('\n')).toContain('#old-anchor');
});

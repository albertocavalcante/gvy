// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { expect, test } from 'bun:test';
import { cancellationDisposition, requestKey } from '../tests/e2e/cancellation.mjs';

const check = (errorText, purpose = '', finished = new Map()) =>
  cancellationDisposition({ errorText, purpose, sequence: 10, url: '/asset.js', resourceType: 'script' }, finished);

test('classifies cancellations using prefetch or a later completed retry', () => {
  const completed = new Map([[requestKey('/asset.js', 'script'), 11]]);
  expect(check('NS_BINDING_ABORTED')).toBe('unrecovered');
  expect(check('NS_BINDING_ABORTED', 'prefetch')).toBe('prefetch');
  expect(check('NS_BINDING_ABORTED', 'Prefetch; anonymous-client-ip')).toBe('prefetch');
  expect(check('NS_BINDING_ABORTED', '', completed)).toBe('recovered');
  expect(check('NS_BINDING_ABORTED', '', new Map([[requestKey('/asset.js', 'script'), 9]]))).toBe('unrecovered');
  expect(check('NS_BINDING_ABORTED', '', new Map([[requestKey('/other.js', 'script'), 11]]))).toBe('unrecovered');
  expect(check('NS_BINDING_ABORTED', '', new Map([[requestKey('/asset.js', 'fetch'), 11]]))).toBe('unrecovered');
  expect(check('net::ERR_ABORTED')).toBe('chromium');
  expect(check('ERR_ABORTED')).toBe('chromium');
  for (const error of ['NS_ERROR_NET_RESET', 'Timeout', 'net::ERR_FAILED', 'unknown', '', undefined, 'NS_BINDING_ABORTED_EXTRA']) {
    expect(check(error, 'prefetch', completed)).toBe('other');
  }
});

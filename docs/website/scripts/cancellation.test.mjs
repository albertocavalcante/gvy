// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { expect, test } from 'bun:test';
import { isNavigationCancellation } from '../tests/e2e/cancellation.mjs';

test('recognizes only known browser cancellation errors', () => {
  expect(isNavigationCancellation('NS_BINDING_ABORTED')).toBe(true);
  expect(isNavigationCancellation('net::ERR_ABORTED')).toBe(true);
  expect(isNavigationCancellation('ERR_ABORTED')).toBe(true);
  for (const error of ['NS_ERROR_NET_RESET', 'Timeout', 'net::ERR_FAILED', 'unknown', '', undefined]) {
    expect(isNavigationCancellation(error)).toBe(false);
  }
  expect(isNavigationCancellation('NS_BINDING_ABORTED_EXTRA')).toBe(false);
});

// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Abstraction for time operations to enable deterministic testing.
 */
export interface Clock {
  /**
   * Returns the current time in milliseconds since Unix epoch.
   */
  now(): number;
}

/**
 * Production implementation using system time.
 */
export class SystemClock implements Clock {
  now(): number {
    return Date.now();
  }
}

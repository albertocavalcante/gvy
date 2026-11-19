// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import type { NetworkPolicyController } from "./networkPolicy";

export interface GuardedHttpOptions {
  readonly controller: NetworkPolicyController;
  readonly timeoutMs: number;
  readonly maxBytes: number;
  readonly maxRedirects?: number;
  readonly signal?: AbortSignal;
  /** All ordinary headers are sent only to the initial origin. */
  readonly headers?: Readonly<Record<string, string>>;
  /** Explicitly trusted credential origin, in canonical form. */
  readonly credentials?: {
    readonly origin: string;
    readonly headers: Readonly<Record<string, string>>;
  };
  /** Explicit trust roots for TLS fixtures or private infrastructure. */
  readonly ca?: string | Buffer | (string | Buffer)[];
}
export class GuardedHttpError extends Error {
  readonly code: string;
  readonly cleanupPath?: string;
}
export interface DownloadResult {
  readonly committed: true;
  /** The private directory remains and needs cleanup if removal failed after commit. */
  readonly cleanupError?: { readonly path: string };
}
/** Results are checked before promise fulfillment; callers must fence later publication. */
export function getText(
  url: string,
  options: GuardedHttpOptions,
): Promise<string>;
export function getJson(
  url: string,
  options: GuardedHttpOptions,
): Promise<unknown>;
/** Synchronously fences the final same-filesystem rename against policy replacement. */
export function downloadFile(
  url: string,
  destination: string,
  options: GuardedHttpOptions,
): Promise<DownloadResult>;

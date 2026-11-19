// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

export type PolicyFailureCode =
  | "INVALID_CONFIGURATION"
  | "CACHE_ONLY"
  | "ORIGIN_NOT_ALLOWED"
  | "INVALID_URL"
  | "STALE_POLICY";

export interface PolicyFailure {
  readonly code: PolicyFailureCode;
}

export interface NetworkPolicy {
  readonly revision: number;
  readonly valid: boolean;
  readonly mode: "cache-only" | "connected";
  readonly allowedOrigins: readonly string[];
  authorize(url: string): PolicyFailure | null;
}

export interface PolicyAdmission {
  readonly revision: number;
  isCurrent(): boolean;
  publish(action: () => void): boolean;
  close(): void;
}

export function normalizeOrigin(
  input: string,
  originOnly?: boolean,
): string | null;
export function parsePolicy(input: unknown, revision: number): NetworkPolicy;
export class NetworkPolicyController {
  constructor(initial?: unknown);
  readonly snapshot: NetworkPolicy;
  replace(input: unknown): unknown[];
  admit(url: string, cancel: () => void): PolicyAdmission | PolicyFailure;
}

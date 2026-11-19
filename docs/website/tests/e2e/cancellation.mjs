// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

/** @param {string} url @param {string} resourceType */
export function requestKey(url, resourceType) {
  return JSON.stringify([url, resourceType]);
}

/**
 * @param {{errorText: string | undefined, purpose: string, sequence: number, url: string, resourceType: string}} request
 * @param {Map<string, number>} finished
 */
export function cancellationDisposition(request, finished) {
  if (request.errorText?.includes("ERR_ABORTED")) return "chromium";
  if (request.errorText !== "NS_BINDING_ABORTED") return "other";
  if (/\bprefetch\b/i.test(request.purpose)) return "prefetch";
  const sequence =
    finished.get(requestKey(request.url, request.resourceType)) ?? 0;
  return sequence > request.sequence ? "recovered" : "unrecovered";
}

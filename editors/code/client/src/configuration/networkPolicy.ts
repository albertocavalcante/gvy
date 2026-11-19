// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { NetworkPolicy, parsePolicy } from "../../../shared/networkPolicy";

/** Minimal inspect surface; workspace and folder values have no authority here. */
export interface PolicyConfiguration {
  inspect(section: string): { globalValue?: unknown } | undefined;
}

/**
 * Read policy independently of the ordinary workspace configuration parser.
 * A malformed permission setting must never disappear through a fallback.
 */
export function readUserNetworkPolicy(
  configuration: PolicyConfiguration,
  revision: number,
): NetworkPolicy {
  const mode = configuration.inspect("network.mode")?.globalValue;
  const allowedOrigins = configuration.inspect(
    "network.allowedOrigins",
  )?.globalValue;
  return parsePolicy(
    {
      ...(mode === undefined ? {} : { mode }),
      ...(allowedOrigins === undefined ? {} : { allowedOrigins }),
    },
    revision,
  );
}

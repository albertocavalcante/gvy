// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { isAbsolute } from "node:path";

export function validateLifetimeInvocation({
  role,
  directory,
  helper,
  profile,
  platform,
  uid,
  euid,
}) {
  if (
    platform !== "linux" ||
    !Number.isSafeInteger(uid) ||
    uid <= 0 ||
    uid !== euid
  )
    throw new Error(
      "Lifetime control requires unchanged non-root Linux identity",
    );
  if (!["host", "init", "worker", "descendant"].includes(role))
    throw new Error("Unknown lifetime control role");
  for (const value of [directory, helper, profile])
    if (typeof value !== "string" || !isAbsolute(value) || value.includes("\0"))
      throw new Error("Lifetime control requires absolute paths");
}

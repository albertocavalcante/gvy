// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

/** @param {string | undefined} errorText */
export function isNavigationCancellation(errorText) {
  return (
    errorText === "NS_BINDING_ABORTED" ||
    (errorText?.includes("ERR_ABORTED") ?? false)
  );
}

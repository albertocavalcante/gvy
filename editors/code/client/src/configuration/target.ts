// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { ConfigurationTarget, workspace } from "vscode";

/**
 * Where commands should persist a setting: the workspace when one is open,
 * user settings otherwise (writing workspace settings without an open
 * folder throws "no workspace is opened").
 */
export function settingsTarget(): ConfigurationTarget {
  return workspace.workspaceFolders?.length
    ? ConfigurationTarget.Workspace
    : ConfigurationTarget.Global;
}

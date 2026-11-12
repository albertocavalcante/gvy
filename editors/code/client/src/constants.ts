// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as vscode from "vscode";

/**
 * Document selector for Groovy-related files.
 * Note: .gradle files are registered as 'groovy' language in package.json.
 * We do NOT include .gradle.kts - those are Kotlin files, not supported.
 */
export const GROOVY_DOCUMENT_SELECTOR: vscode.DocumentSelector = [
  { language: "groovy" },
  { language: "jenkinsfile" },
];

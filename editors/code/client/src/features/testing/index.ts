// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as vscode from "vscode";
import { GroovyTestController } from "./GroovyTestController";
import { TestService } from "./TestService";
import { CoverageService } from "./CoverageService";
import { TestCodeLensProvider } from "./TestCodeLensProvider";
import { getClient, onClientStarted } from "../../server/client";
import { LSPTestExecutionService } from "./LSPTestExecutionService";
import { TestItemRegistry } from "./TestItemRegistry";

export function registerTestingFeatures(
  context: vscode.ExtensionContext,
  logger: vscode.OutputChannel,
) {
  // The language server is the source of truth for discovery, commands and
  // results; the service resolves the current client on every request.
  const testService = new TestService(getClient);
  const itemRegistry = new TestItemRegistry();
  const executionService = new LSPTestExecutionService(
    testService,
    logger,
    context.extensionPath,
    itemRegistry.describe,
  );
  const coverageService = new CoverageService(testService, logger);

  const controller = new GroovyTestController(
    context,
    executionService,
    itemRegistry,
    testService,
    coverageService,
  );
  // Registered before the server starts: discover again on every (re)start.
  context.subscriptions.push(onClientStarted(() => controller.refreshTests()));

  context.subscriptions.push(
    vscode.languages.registerCodeLensProvider(
      [{ language: "groovy" }, { language: "jenkinsfile" }],
      new TestCodeLensProvider(testService),
    ),
  );
}

import * as vscode from "vscode";
import { GradleExecutionService } from "./GradleExecutionService";
import { GroovyTestController } from "./GroovyTestController";
import { TestService } from "./TestService";
import { CoverageService } from "./CoverageService";
import { TestCodeLensProvider } from "./TestCodeLensProvider";
import { getClient } from "../../server/client";
import { LSPTestExecutionService } from "./LSPTestExecutionService";
import { ITestExecutionService } from "./ITestExecutionService";

export function registerTestingFeatures(
  context: vscode.ExtensionContext,
  logger: vscode.OutputChannel,
) {
  // Get the LanguageClient
  const client = getClient();
  const testService = client ? new TestService(client) : undefined;

  // If LSP is not available, we can't fully initialize testing features yet.
  // We rely on extension.ts calling this AFTER startClient.
  // However, if we want to support fallback for non-LSP usage in the future,
  // we would handle that here. For now, we assume LSP is the source of truth.
  if (!testService) {
    logger.appendLine(
      "[Testing] LSP client not ready. Testing features may be limited.",
    );
    // return; // Or continue with limited functionality?
    // Existing code allowed creating controller without testService for limited use.
  }

  // Use LSPTestExecutionService which delegates to the server
  // We pass the testService (even if undefined/null initially, though it should be defined if client is running)
  // But LSPTestExecutionService requires testService in constructor.
  // We'll cast/check inside.

  let executionService: ITestExecutionService;

  if (testService) {
    executionService = new LSPTestExecutionService(
      testService,
      logger,
      context.extensionPath,
    );
  } else {
    // Fallback or placeholder if somehow registered without client
    // We can use GradleExecutionService as a dumb fallback purely for the existing behavior
    // or just error out.
    // Given the duplicate call issue, if we fix extension.ts, this branch is unreachable.
    // If we don't fix extension.ts, this branch IS reached on first call.
    // To lead to a valid state, we'll default to Gradle logic (the old behavior)
    // but acknowledge it's temporary until the second call updates it?
    // Wait, second call creates NEW controller, doesn't update old one.

    // We should really prevent the first call or make this robust.
    // For now, let's assume we fix extension.ts.
    executionService = new GradleExecutionService(
      logger,
      context.extensionPath,
    );
  }

  // Create coverage service (legacy Gradle support)
  // TODO: Move coverage logic to LSP or LSPTestExecutionService
  const coverageService = new CoverageService(logger);

  new GroovyTestController(
    context,
    executionService,
    testService,
    coverageService,
  );

  // Register CodeLens provider
  if (testService) {
    const codeLensProvider = new TestCodeLensProvider(testService);
    context.subscriptions.push(
      vscode.languages.registerCodeLensProvider(
        [{ language: "groovy" }, { language: "jenkinsfile" }],
        codeLensProvider,
      ),
    );
  }
}

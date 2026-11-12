import * as vscode from "vscode";
import { getClient, onClientStarted } from "../../server/client";
import { DependencyService } from "./DependencyService";
import { DependencyTreeProvider } from "./DependencyTreeProvider";

export { DependencyService } from "./DependencyService";
export { DependencyTreeProvider } from "./DependencyTreeProvider";
export type { DependencyInfo, DependenciesResult } from "./DependencyService";

export function registerDependencyFeatures(
  context: vscode.ExtensionContext,
): DependencyTreeProvider {
  const provider = new DependencyTreeProvider(new DependencyService(getClient));

  context.subscriptions.push(
    provider,
    onClientStarted(() => provider.refresh()),
    vscode.window.registerTreeDataProvider("groovyDependencies", provider),
    vscode.commands.registerCommand("groovy.dependencies.refresh", () =>
      provider.refresh(),
    ),
    vscode.commands.registerCommand(
      "groovy.dependencies.copyCoordinate",
      async (item?: { coordinate?: string }) => {
        if (!item?.coordinate) {
          return;
        }
        try {
          await vscode.env.clipboard.writeText(item.coordinate);
          vscode.window.showInformationMessage(`Copied: ${item.coordinate}`);
        } catch {
          vscode.window.showErrorMessage(
            "Failed to copy dependency coordinate to clipboard.",
          );
        }
      },
    ),
  );
  return provider;
}

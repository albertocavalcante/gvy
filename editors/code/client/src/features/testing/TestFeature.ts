import * as vscode from "vscode";
import { SpockGenerator } from "./SpockGenerator";
import * as path from "path";

export class TestFeature implements vscode.Disposable {
  private disposables: vscode.Disposable[] = [];
  private generator: SpockGenerator;

  constructor() {
    this.generator = new SpockGenerator();
    this.registerCommands();
  }

  private registerCommands() {
    this.disposables.push(
      vscode.commands.registerCommand(
        "groovy.test.generate",
        async (uri?: vscode.Uri) => {
          const targetUri = uri || vscode.window.activeTextEditor?.document.uri;
          if (!targetUri) {
            vscode.window.showErrorMessage(
              "Open a Groovy file to generate tests.",
            );
            return;
          }
          await this.generateTest(targetUri);
        },
      ),
    );
  }

  private async generateTest(sourceUri: vscode.Uri) {
    try {
      const document = await vscode.workspace.openTextDocument(sourceUri);

      // 1. Get Symbols from LSP
      const symbols = await vscode.commands.executeCommand<
        vscode.DocumentSymbol[]
      >("vscode.executeDocumentSymbolProvider", sourceUri);

      if (!symbols || symbols.length === 0) {
        vscode.window.showErrorMessage(
          "No symbols found. Ensure the Language Server is ready.",
        );
        return;
      }

      // 2. Analyze
      // TODO: Handle multiple classes in one file better
      const classSymbol = symbols.find(
        (s) => s.kind === vscode.SymbolKind.Class,
      );
      if (!classSymbol) {
        vscode.window.showErrorMessage("No class found in file.");
        return;
      }

      const methods = classSymbol.children.filter(
        (s) => s.kind === vscode.SymbolKind.Method,
      );

      // 3. Generate Content
      const packageName = this.generator.detectPackage(document.getText());
      const content = this.generator.generateSpec(
        classSymbol.name,
        methods.map((m) => ({ name: m.name })),
        packageName,
      );

      // 4. Resolve Path & Write
      const testPath = this.generator.resolveTestPath(sourceUri.fsPath);
      if (!testPath) {
        vscode.window.showErrorMessage(
          "Could not resolve test path. Structure 'src/main' not found.",
        );
        return;
      }
      const testUri = vscode.Uri.file(testPath);

      // Check if file already exists using async API
      try {
        await vscode.workspace.fs.stat(testUri);
        vscode.window.showWarningMessage(
          `Test file already exists: ${path.basename(testUri.fsPath)}`,
        );
        // TODO: V2 - Prompt to overwrite or merge
        return;
      } catch {
        // File doesn't exist, which is what we want
      }

      // Ensure directory exists using async API
      const dirUri = vscode.Uri.file(path.dirname(testUri.fsPath));
      await vscode.workspace.fs.createDirectory(dirUri);

      // Write file using async API
      const contentBytes = new TextEncoder().encode(content);
      await vscode.workspace.fs.writeFile(testUri, contentBytes);

      // Open file
      const doc = await vscode.workspace.openTextDocument(testUri);
      await vscode.window.showTextDocument(doc);
    } catch (error) {
      vscode.window.showErrorMessage(`Failed to generate test: ${error}`);
    }
  }

  public dispose() {
    this.disposables.forEach((d) => d.dispose());
  }
}

import * as vscode from "vscode";
import { SpockGenerator } from "./SpockGenerator";
import * as path from "path";
import { writeFile } from "node:fs/promises";

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
          if (targetUri.scheme !== "file") {
            vscode.window.showErrorMessage(
              "Test generation requires a file on the extension host filesystem.",
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
      const testUri = vscode.Uri.file(testPath);

      // Check if file already exists using async API
      try {
        await vscode.workspace.fs.stat(testUri);
        vscode.window.showWarningMessage(
          `Test file already exists: ${path.basename(testUri.fsPath)}`,
        );
        // TODO: V2 - Prompt to overwrite or merge
        return;
      } catch (error) {
        if (
          error instanceof vscode.FileSystemError &&
          error.code === "FileNotFound"
        ) {
          // File doesn't exist, which is what we want. Continue.
        } else {
          // Re-throw other unexpected errors to be caught by the outer try-catch block.
          throw error;
        }
      }

      // Ensure directory exists using async API
      const dirUri = vscode.Uri.file(path.dirname(testUri.fsPath));
      await vscode.workspace.fs.createDirectory(dirUri);

      const contentBytes = new TextEncoder().encode(content);
      if (!(await createNewSpecFile(testUri, contentBytes))) {
        vscode.window.showWarningMessage(
          `Could not create test file: ${path.basename(testUri.fsPath)}. ` +
            "The destination may already exist or could not be written.",
        );
        return;
      }

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

/** Creates native specification files without replacing an existing path. */
export async function createNewSpecFile(
  uri: vscode.Uri,
  contents: Uint8Array,
): Promise<boolean> {
  if (uri.scheme !== "file") {
    throw new Error(
      `Cannot create a specification for URI scheme: ${uri.scheme}`,
    );
  }
  try {
    // TODO(#1781): Revisit when editor resource creation preserves exclusive semantics.
    //   See: https://github.com/albertocavalcante/gvy/issues/1781
    await writeFile(uri.fsPath, contents, { flag: "wx" });
    return true;
  } catch (error: unknown) {
    if (
      typeof error === "object" &&
      error !== null &&
      "code" in error &&
      error.code === "EEXIST"
    ) {
      return false;
    }
    throw error;
  }
}

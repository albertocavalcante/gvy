import * as assert from "assert";
import { createHash } from "crypto";
import { existsSync, readFileSync, realpathSync } from "fs";
import * as vscode from "vscode";

suite("Implementation navigation with the current server", () => {
  let document: vscode.TextDocument;

  suiteSetup(async function () {
    this.timeout(60000);
    const extension = vscode.extensions.getExtension("albertocavalcante.gvy");
    assert.ok(extension, "Groovy extension must be installed");
    const workspace = vscode.workspace.workspaceFolders?.[0];
    assert.ok(workspace, "Editor tests require the source fixture workspace");
    const packagedRoot = process.env.GVY_TEST_EXTENSION_ROOT;
    if (packagedRoot) {
      assert.strictEqual(
        realpathSync(extension.extensionUri.fsPath),
        realpathSync(packagedRoot),
        "Editor must load the unpacked VSIX",
      );
    }

    const candidate =
      process.env.GVY_TEST_SERVER_JAR ||
      vscode.Uri.joinPath(
        workspace.uri,
        "../../gls/build/editor-server/gls.jar",
      ).fsPath;
    assert.ok(
      existsSync(candidate),
      "Build :gls:stageEditorServer and prepare that JAR before editor tests",
    );
    const bundled = vscode.Uri.joinPath(
      extension.extensionUri,
      "server/gls.jar",
    );
    const hash = (file: string) =>
      createHash("sha256").update(readFileSync(file)).digest("hex");
    assert.strictEqual(
      hash(bundled.fsPath),
      hash(candidate),
      "Editor must run the staged candidate server",
    );

    await extension.activate();
    document = await vscode.workspace.openTextDocument(
      vscode.Uri.joinPath(
        workspace.uri,
        "client/src/test/fixtures/Implementation.groovy",
      ),
    );
    await vscode.window.showTextDocument(document);
  });

  async function implementations(name: string) {
    const offset = document.getText().indexOf(name);
    assert.ok(offset >= 0, `Missing fixture symbol ${name}`);
    const result = await vscode.commands.executeCommand<
      Array<vscode.Location | vscode.LocationLink>
    >(
      "vscode.executeImplementationProvider",
      document.uri,
      document.positionAt(offset),
    );
    assert.ok(result, "Editor implementation provider must return a result");
    return result.map((location) =>
      "targetUri" in location
        ? { uri: location.targetUri, range: location.targetRange }
        : location,
    );
  }

  function assertTarget(
    locations: Array<{ uri: vscode.Uri; range: vscode.Range }>,
    target: string,
  ) {
    assert.strictEqual(
      locations.length,
      1,
      "Exactly the concrete implementation must be returned",
    );
    assert.strictEqual(
      realpathSync(locations[0].uri.fsPath),
      realpathSync(document.uri.fsPath),
    );
    const offset = document.getText().indexOf(target);
    assert.ok(offset >= 0, `Missing fixture target ${target}`);
    const expected = document.positionAt(offset);
    assert.strictEqual(locations[0].range.start.line, expected.line);
  }

  test("Go to Implementation finds the concrete interface implementation", async () => {
    assertTarget(
      await implementations("EditorGreeter"),
      "class FriendlyEditorGreeter",
    );
  });

  test("Go to Implementation finds the concrete method", async () => {
    assertTarget(
      await implementations("greet()"),
      "String greet() { 'hello' }",
    );
  });

  test("Unrelated concrete class is not an implementation target", async () => {
    assert.deepStrictEqual(await implementations("UnrelatedEditorGreeter"), []);
  });

  test("Generic method navigation specializes each implementation and excludes the wrong overload", async () => {
    const locations = await implementations("convert(T value)");
    const targets = [
      "String convert(String value)",
      "Integer convert(Integer value)",
      "java.util.Date convert(java.util.Date value)",
    ];
    assert.strictEqual(locations.length, targets.length);
    for (const location of locations) {
      assert.strictEqual(
        realpathSync(location.uri.fsPath),
        realpathSync(document.uri.fsPath),
      );
    }
    const expectedLines = targets.map((target) => {
      const offset = document.getText().indexOf(target);
      assert.ok(offset >= 0, `Missing fixture target ${target}`);
      return document.positionAt(offset).line;
    });
    assert.deepStrictEqual(
      locations
        .map((location) => location.range.start.line)
        .sort((a, b) => a - b),
      expectedLines.sort((a, b) => a - b),
    );
  });

  test("Generic default method navigation retains the inherited implementation", async () => {
    assertTarget(
      await implementations("echo(T value)"),
      "default T echo(T value)",
    );
  });
});

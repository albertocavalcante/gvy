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

  test("Semantic tokens keep parameter names and types in separate exact spans", async () => {
    const legend =
      await vscode.commands.executeCommand<vscode.SemanticTokensLegend>(
        "vscode.provideDocumentSemanticTokensLegend",
        document.uri,
      );
    const result = await vscode.commands.executeCommand<vscode.SemanticTokens>(
      "vscode.provideDocumentSemanticTokens",
      document.uri,
    );
    assert.ok(legend, "The server must provide a semantic token legend");
    assert.ok(result, "The server must provide semantic tokens");
    assert.strictEqual(result.data.length % 5, 0);
    const tokens: Array<{
      line: number;
      start: number;
      length: number;
      type: string;
      modifiers: number;
    }> = [];
    let line = 0;
    let start = 0;
    for (let offset = 0; offset < result.data.length; offset += 5) {
      const deltaLine = result.data[offset];
      line += deltaLine;
      start = (deltaLine === 0 ? start : 0) + result.data[offset + 1];
      tokens.push({
        line,
        start,
        length: result.data[offset + 2],
        type: legend.tokenTypes[result.data[offset + 3]],
        modifiers: result.data[offset + 4],
      });
    }
    const declarationIndex = legend.tokenModifiers.indexOf("declaration");
    const definitionIndex = legend.tokenModifiers.indexOf("definition");
    assert.ok(declarationIndex >= 0);
    assert.ok(definitionIndex >= 0);
    for (const declaration of [
      "String convert(String value)",
      "Integer convert(Integer value)",
      "java.util.Date convert(java.util.Date value)",
    ]) {
      const declarationOffset = document.getText().indexOf(declaration);
      assert.ok(declarationOffset >= 0);
      const parameter = document.positionAt(
        declarationOffset + declaration.indexOf("value"),
      );
      const typeEnd = declaration.lastIndexOf(" value");
      const typeName = declaration
        .slice(declaration.indexOf("(") + 1, typeEnd)
        .split(".")
        .pop()!;
      const type = document.positionAt(
        declarationOffset + typeEnd - typeName.length,
      );
      const at = (position: vscode.Position) =>
        tokens.filter(
          (token) =>
            token.line === position.line && token.start === position.character,
        );
      assert.deepStrictEqual(
        at(parameter),
        [
          {
            line: parameter.line,
            start: parameter.character,
            length: "value".length,
            type: "parameter",
            modifiers: (1 << declarationIndex) | (1 << definitionIndex),
          },
        ],
        `Parameter declaration in ${declaration}`,
      );
      assert.deepStrictEqual(
        at(type),
        [
          {
            line: type.line,
            start: type.character,
            length: typeName.length,
            type: "class",
            modifiers: 0,
          },
        ],
        `Parameter type in ${declaration}`,
      );
      const onLine = tokens.filter((token) => token.line === parameter.line);
      for (let index = 1; index < onLine.length; index++) {
        assert.ok(
          onLine[index - 1].start + onLine[index - 1].length <=
            onLine[index].start,
          `Overlapping semantic tokens in ${declaration}: ${JSON.stringify(onLine)}`,
        );
      }
    }
  });

  for (const declaration of [
    "String convert(String value) { value }",
    "def defaults(value, copy = value) { value }",
    "String memoized(String value) { value }",
  ]) {
    test(`Find References isolates all bindings in ${declaration}`, async () => {
      const offset = document.getText().indexOf(declaration);
      assert.ok(offset >= 0);
      const locations = await vscode.commands.executeCommand<vscode.Location[]>(
        "vscode.executeReferenceProvider",
        document.uri,
        document.positionAt(offset + declaration.lastIndexOf("value")),
      );
      assert.ok(locations, "The server must return the parameter references");
      for (const location of locations) {
        assert.strictEqual(location.uri.toString(), document.uri.toString());
        assert.strictEqual(document.getText(location.range), "value");
      }
      assert.deepStrictEqual(
        locations
          .map((location) => [
            document.offsetAt(location.range.start),
            document.offsetAt(location.range.end),
          ])
          .sort((left, right) => left[0] - right[0]),
        [...declaration.matchAll(/\bvalue\b/g)].map((match) => [
          offset + match.index!,
          offset + match.index! + "value".length,
        ]),
      );
    });
  }

  test("Call hierarchy round trips keep UTF16 caller and callee ranges", async () => {
    const source = document.getText();
    const targetDeclaration = "def target() {}";
    const callerDeclaration = "def caller() { target() }";
    const targetOffset = source.indexOf(targetDeclaration);
    const callerOffset = source.indexOf(callerDeclaration);
    assert.ok(targetOffset >= 0 && callerOffset >= 0);
    const offsets = (range: vscode.Range) => [
      document.offsetAt(range.start),
      document.offsetAt(range.end),
    ];
    const prepared = await vscode.commands.executeCommand<
      vscode.CallHierarchyItem[]
    >(
      "vscode.prepareCallHierarchy",
      document.uri,
      document.positionAt(targetOffset + "def ".length),
    );
    assert.ok(prepared);
    assert.deepStrictEqual(
      prepared.map((item) => item.name),
      ["target"],
    );
    const targetRange = [targetOffset, targetOffset + targetDeclaration.length];
    assert.deepStrictEqual(offsets(prepared[0].range), targetRange);
    assert.deepStrictEqual(offsets(prepared[0].selectionRange), targetRange);
    const incoming = await vscode.commands.executeCommand<
      vscode.CallHierarchyIncomingCall[]
    >("vscode.provideIncomingCalls", prepared[0]);
    assert.ok(incoming);
    assert.deepStrictEqual(
      incoming.map((call) => call.from.name),
      ["caller"],
    );
    assert.strictEqual(
      incoming[0].from.uri.toString(),
      document.uri.toString(),
    );
    assert.deepStrictEqual(offsets(incoming[0].from.range), [
      callerOffset,
      callerOffset + callerDeclaration.length,
    ]);
    const callOffset = source.indexOf("target()", callerOffset);
    assert.deepStrictEqual(incoming[0].fromRanges.map(offsets), [
      [callOffset, callOffset + "target".length],
    ]);
    const outgoing = await vscode.commands.executeCommand<
      vscode.CallHierarchyOutgoingCall[]
    >("vscode.provideOutgoingCalls", incoming[0].from);
    assert.ok(outgoing);
    assert.deepStrictEqual(
      outgoing.map((call) => call.to.name),
      ["target"],
    );
    assert.strictEqual(outgoing[0].to.uri.toString(), document.uri.toString());
    assert.deepStrictEqual(offsets(outgoing[0].to.range), targetRange);
    assert.deepStrictEqual(outgoing[0].fromRanges.map(offsets), [
      [callOffset, callOffset + "target()".length],
    ]);
  });

  test("Rename applies exact parameter edits through the current server", async () => {
    await vscode.window.showTextDocument(document);
    const original = document.getText();
    const declaration = "String convert(String value) { value }";
    const offset = original.indexOf(declaration);
    assert.ok(offset >= 0);
    const parameterOffset = offset + declaration.indexOf("value");
    const edit = await vscode.commands.executeCommand<vscode.WorkspaceEdit>(
      "vscode.executeDocumentRenameProvider",
      document.uri,
      document.positionAt(parameterOffset),
      "convertedValue",
    );
    assert.ok(edit, "The server must return a rename edit");
    const entries = edit.entries();
    assert.strictEqual(entries.length, 1);
    assert.strictEqual(
      realpathSync(entries[0][0].fsPath),
      realpathSync(document.uri.fsPath),
    );
    const edits = entries[0][1];
    assert.deepStrictEqual(
      edits
        .map((entry) => document.offsetAt(entry.range.start))
        .sort((a, b) => a - b),
      [parameterOffset, offset + declaration.lastIndexOf("value")],
    );
    for (const entry of edits) {
      assert.strictEqual(document.getText(entry.range), "value");
      assert.strictEqual(entry.newText, "convertedValue");
    }
    try {
      assert.ok(await vscode.workspace.applyEdit(edit));
      assert.strictEqual(
        document.getText(),
        original.replace(
          declaration,
          declaration.replace(/\bvalue\b/g, "convertedValue"),
        ),
      );
    } finally {
      const restore = new vscode.WorkspaceEdit();
      restore.replace(
        document.uri,
        new vscode.Range(
          document.positionAt(0),
          document.positionAt(document.getText().length),
        ),
        original,
      );
      assert.ok(await vscode.workspace.applyEdit(restore));
      assert.strictEqual(document.getText(), original);
    }
  });
  for (const declaration of [
    "def defaults(value, copy = value) { value }",
    "String memoized(String value) { value }",
  ]) {
    test(`Rename from a body updates all bindings in ${declaration}`, async () => {
      await vscode.window.showTextDocument(document);
      const original = document.getText();
      const offset = original.indexOf(declaration);
      assert.ok(offset >= 0);
      const edit = await vscode.commands.executeCommand<vscode.WorkspaceEdit>(
        "vscode.executeDocumentRenameProvider",
        document.uri,
        document.positionAt(offset + declaration.lastIndexOf("value")),
        "renamedValue",
      );
      assert.ok(edit, "The server must return a complete rename edit");
      const entries = edit.entries();
      assert.strictEqual(entries.length, 1);
      assert.strictEqual(entries[0][0].toString(), document.uri.toString());
      assert.strictEqual(
        entries[0][1].length,
        (declaration.match(/\bvalue\b/g) || []).length,
      );
      for (const entry of entries[0][1]) {
        assert.strictEqual(document.getText(entry.range), "value");
        assert.strictEqual(entry.newText, "renamedValue");
      }
      try {
        assert.ok(await vscode.workspace.applyEdit(edit));
        assert.strictEqual(
          document.getText(),
          original.replace(
            declaration,
            declaration.replace(/\bvalue\b/g, "renamedValue"),
          ),
        );
      } finally {
        const restore = new vscode.WorkspaceEdit();
        restore.replace(
          document.uri,
          new vscode.Range(
            document.positionAt(0),
            document.positionAt(document.getText().length),
          ),
          original,
        );
        assert.ok(await vscode.workspace.applyEdit(restore));
        assert.strictEqual(document.getText(), original);
      }
    });
  }
});

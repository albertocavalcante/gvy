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

  test("Semantic tokens cover exact identifiers and keywords without edits", async () => {
    const workspace = vscode.workspace.workspaceFolders?.[0];
    assert.ok(workspace);
    const semantic = await vscode.workspace.openTextDocument(
      vscode.Uri.joinPath(
        workspace.uri,
        "client/src/test/fixtures/SemanticTokens.groovy",
      ),
    );
    await vscode.window.showTextDocument(semantic);
    const source = semantic.getText();
    const version = semantic.version;
    const legend =
      await vscode.commands.executeCommand<vscode.SemanticTokensLegend>(
        "vscode.provideDocumentSemanticTokensLegend",
        semantic.uri,
      );
    const result = await vscode.commands.executeCommand<vscode.SemanticTokens>(
      "vscode.provideDocumentSemanticTokens",
      semantic.uri,
    );
    assert.ok(legend);
    assert.ok(result);
    assert.strictEqual(result.data.length % 5, 0);
    const tokens: Array<{ text: string; type: string; offset: number }> = [];
    let line = 0;
    let character = 0;
    for (let index = 0; index < result.data.length; index += 5) {
      const [deltaLine, deltaStart, length, type] = result.data.slice(
        index,
        index + 5,
      );
      line += deltaLine;
      character = deltaLine === 0 ? character + deltaStart : deltaStart;
      const start = new vscode.Position(line, character);
      const range = new vscode.Range(start, start.translate(0, length));
      assert.ok(
        line < semantic.lineCount &&
          range.end.character <= semantic.lineAt(line).text.length,
      );
      tokens.push({
        text: semantic.getText(range),
        type: legend.tokenTypes[type],
        offset: semantic.offsetAt(start),
      });
    }
    for (const [name, type] of [
      ["TokenExample", "class"],
      ["echo", "method"],
      ["value", "parameter"],
      ["class", "keyword"],
      ["return", "keyword"],
    ]) {
      assert.ok(
        tokens.some(
          (token) =>
            token.text === name &&
            token.type === type &&
            token.offset === source.indexOf(name),
        ),
        `Missing exact ${type} token for ${name}: ${JSON.stringify(tokens)}`,
      );
    }
    assert.deepStrictEqual(
      tokens
        .filter((token) => token.type === "keyword")
        .map((token) => token.text),
      ["class", "return"],
    );
    assert.strictEqual(semantic.getText(), source);
    assert.strictEqual(semantic.version, version);
  });

  test("Folding returns import, comment and owned structural ranges without edits", async () => {
    const workspace = vscode.workspace.workspaceFolders?.[0];
    assert.ok(workspace);
    const folding = await vscode.workspace.openTextDocument(
      vscode.Uri.joinPath(
        workspace.uri,
        "client/src/test/fixtures/FoldingRanges.groovy",
      ),
    );
    await vscode.window.showTextDocument(folding);
    const source = folding.getText();
    const version = folding.version;
    const ranges = await vscode.commands.executeCommand<vscode.FoldingRange[]>(
      "vscode.executeFoldingRangeProvider",
      folding.uri,
    );
    assert.ok(ranges, "Editor folding provider must return ranges");
    const includes = (
      kind: vscode.FoldingRangeKind,
      start: number,
      end: number,
    ) =>
      ranges.some(
        (range) =>
          range.kind === kind && range.start === start && range.end === end,
      );
    assert.ok(includes(vscode.FoldingRangeKind.Imports, 0, 1));
    assert.ok(includes(vscode.FoldingRangeKind.Comment, 3, 5));
    assert.ok(includes(vscode.FoldingRangeKind.Region, 6, 9));
    assert.ok(includes(vscode.FoldingRangeKind.Region, 7, 8));
    assert.ok(
      ranges.every(
        (range) =>
          range.start >= 0 &&
          range.end > range.start &&
          range.end < folding.lineCount,
      ),
    );
    assert.strictEqual(folding.getText(), source);
    assert.strictEqual(folding.version, version);
  });

  test("Smart selection preserves ordered nested source ranges without edits", async () => {
    const workspace = vscode.workspace.workspaceFolders?.[0];
    assert.ok(workspace);
    const selection = await vscode.workspace.openTextDocument(
      vscode.Uri.joinPath(
        workspace.uri,
        "client/src/test/fixtures/SelectionRanges.groovy",
      ),
    );
    const editor = await vscode.window.showTextDocument(selection);
    const source = selection.getText();
    const version = selection.version;
    const body = source.indexOf("value + 1");
    const declaration = source.indexOf("value");
    const offsets = [body, declaration];
    const positions = [
      ...offsets.map((offset) => selection.positionAt(offset + 1)),
      selection.positionAt(source.length),
    ];
    const result = await vscode.commands.executeCommand<
      vscode.SelectionRange[]
    >("vscode.executeSelectionRangeProvider", selection.uri, positions);
    assert.ok(result);
    assert.strictEqual(result.length, positions.length);
    const documentRange = new vscode.Range(
      selection.positionAt(0),
      selection.positionAt(source.length),
    );
    for (const [index, first] of result.entries()) {
      const expected =
        index < offsets.length
          ? new vscode.Range(
              selection.positionAt(offsets[index]),
              selection.positionAt(offsets[index] + 5),
            )
          : new vscode.Range(positions[index], positions[index]);
      assert.ok(first.range.isEqual(expected));
      const ranges: vscode.Range[] = [];
      let current: vscode.SelectionRange | undefined = first;
      while (current) {
        ranges.push(current.range);
        assert.ok(
          ranges.length <= 64,
          "Selection parent depth must be bounded",
        );
        if (current.parent) {
          assert.ok(current.parent.range.contains(current.range));
          // VS Code merges the server response with its word provider and can repeat a parent.
          // Direct protocol tests enforce strict growth in the server's own chain.
        }
        current = current.parent;
      }
      assert.ok(ranges[ranges.length - 1].isEqual(documentRange));
      if (index === 0) {
        assert.ok(
          ranges.some((range) => selection.getText(range) === "value + 1"),
        );
      }
    }
    editor.selection = new vscode.Selection(positions[0], positions[0]);
    await vscode.commands.executeCommand("editor.action.smartSelect.expand");
    assert.strictEqual(selection.getText(editor.selection), "value");
    await vscode.commands.executeCommand("editor.action.smartSelect.expand");
    assert.strictEqual(selection.getText(editor.selection), "value + 1");
    assert.strictEqual(selection.version, version);
    assert.strictEqual(selection.getText(), source);
  });

  test("Script class literals retain resolved nested class references", async () => {
    const workspace = vscode.workspace.workspaceFolders?.[0];
    assert.ok(workspace);
    const script = await vscode.workspace.openTextDocument(
      vscode.Uri.joinPath(
        workspace.uri,
        "client/src/test/fixtures/ScriptClassLiterals.groovy",
      ),
    );
    await vscode.window.showTextDocument(script);
    const source = script.getText();
    const offsets = [...source.matchAll(/\bEntry\b/g)].map(
      (match) => match.index,
    );
    assert.strictEqual(offsets.length, 2);
    for (const offset of offsets) {
      const locations = await vscode.commands.executeCommand<vscode.Location[]>(
        "vscode.executeReferenceProvider",
        script.uri,
        script.positionAt(offset + 1),
      );
      assert.ok(locations);
      assert.deepStrictEqual(
        locations
          .map((location) => [
            location.uri.toString(),
            script.offsetAt(location.range.start),
            script.offsetAt(location.range.end),
          ])
          .sort((left, right) => Number(left[1]) - Number(right[1])),
        offsets.map((start) => [script.uri.toString(), start, start + 5]),
      );
    }
  });

  test("Class references preserve imported aliases and UTF16 source ranges", async () => {
    const workspace = vscode.workspace.workspaceFolders?.[0];
    assert.ok(workspace);
    const aliases = await vscode.workspace.openTextDocument(
      vscode.Uri.joinPath(
        workspace.uri,
        "client/src/test/fixtures/ImportedAliases.groovy",
      ),
    );
    await vscode.window.showTextDocument(aliases);
    const source = aliases.getText();
    const declaration =
      source.indexOf("class EditorAliasTarget") + "class ".length;
    const usage = source.indexOf("new EditorAlias(") + "new ".length;
    const spans: Array<[number, string]> = [
      [source.indexOf("EditorAliasTarget"), "EditorAliasTarget"],
      [declaration, "EditorAliasTarget"],
      [usage, "EditorAlias"],
      [source.indexOf("EditorAliasTarget.class"), "EditorAliasTarget"],
      [source.lastIndexOf("new EditorAlias(") + "new ".length, "EditorAlias"],
    ];
    const expected = spans
      .map(([offset, name]) => [offset, offset + name.length])
      .sort((left, right) => left[0] - right[0]);
    for (const offset of [declaration, usage]) {
      const locations = await vscode.commands.executeCommand<vscode.Location[]>(
        "vscode.executeReferenceProvider",
        aliases.uri,
        aliases.positionAt(offset + 1),
      );
      assert.ok(locations);
      for (const location of locations) {
        assert.strictEqual(location.uri.toString(), aliases.uri.toString());
      }
      assert.deepStrictEqual(
        locations
          .map((location) => [
            aliases.offsetAt(location.range.start),
            aliases.offsetAt(location.range.end),
          ])
          .sort((left, right) => left[0] - right[0]),
        expected,
      );
    }
  });

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

  test("Prepare Rename proves exact bindings and rejects comment text without edits", async () => {
    const workspace = vscode.workspace.workspaceFolders?.[0];
    assert.ok(workspace);
    const preparedDocument = await vscode.workspace.openTextDocument(
      vscode.Uri.joinPath(
        workspace.uri,
        "client/src/test/fixtures/PrepareRename.groovy",
      ),
    );
    await vscode.window.showTextDocument(preparedDocument);
    const source = preparedDocument.getText();
    const version = preparedDocument.version;
    for (const declaration of [
      "def call(value, copy = value) { value }",
      "String memo(String value) { value }",
    ]) {
      const offset =
        source.indexOf(declaration) + declaration.lastIndexOf("value");
      assert.ok(source.includes(declaration));
      const prepared = await vscode.commands.executeCommand<{
        range: vscode.Range;
        placeholder: string;
      }>(
        "vscode.prepareRename",
        preparedDocument.uri,
        preparedDocument.positionAt(offset + 1),
      );
      assert.ok(prepared);
      assert.strictEqual(prepared.placeholder, "value");
      assert.deepStrictEqual(
        [
          preparedDocument.offsetAt(prepared.range.start),
          preparedDocument.offsetAt(prepared.range.end),
        ],
        [offset, offset + "value".length],
      );
    }
    await assert.rejects(
      async () =>
        vscode.commands.executeCommand(
          "vscode.prepareRename",
          preparedDocument.uri,
          preparedDocument.positionAt(source.indexOf("/* value */") + 3),
        ),
      /rename/i,
    );
    assert.strictEqual(preparedDocument.getText(), source);
    assert.strictEqual(preparedDocument.version, version);
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

// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const textmate = require("vscode-textmate");
const oniguruma = require("vscode-oniguruma");

const harnessRoot = path.resolve(__dirname, "../..");
const extensionRoot = process.env.GVY_TEST_EXTENSION_ROOT || harnessRoot;
const read = (file) => fs.readFileSync(path.join(extensionRoot, file), "utf8");

test("GDSL snippets offer only the supported global script scope", () => {
  const snippets = JSON.parse(read("resources/snippets/gdsl.json"));
  for (const name of ["GDSL contributor", "GDSL context"]) {
    const body = snippets[name].body.join("\n");
    assert.match(body, /scriptScope\(\)/);
    assert.doesNotMatch(body, /closureScope|classScope|annotatedScope/);
  }
});

test("the registered GDSL grammar tokenizes descriptor comments and strings", async () => {
  const manifest = JSON.parse(read("package.json"));
  const binding = manifest.contributes.grammars.find(
    (grammar) => grammar.language === "gdsl",
  );
  assert.ok(binding, "GDSL must have a registered grammar");
  const wasm = fs.readFileSync(
    require.resolve("vscode-oniguruma/release/onig.wasm"),
  );
  await oniguruma.loadWASM(
    wasm.buffer.slice(wasm.byteOffset, wasm.byteOffset + wasm.byteLength),
  );
  const registry = new textmate.Registry({
    onigLib: Promise.resolve({
      createOnigScanner: (patterns) => new oniguruma.OnigScanner(patterns),
      createOnigString: (value) => new oniguruma.OnigString(value),
    }),
    loadGrammar: async (scope) =>
      scope === binding.scopeName
        ? textmate.parseRawGrammar(read(binding.path), binding.path)
        : null,
  });
  const grammar = await registry.loadGrammar(binding.scopeName);
  assert.ok(grammar, "registered GDSL grammar must load");
  const fixture = fs.readFileSync(
    path.join(harnessRoot, "client/src/test/fixtures/jenkins-descriptor.gdsl"),
    "utf8",
  );
  let state = textmate.INITIAL;
  const tokensByLine = fixture.split(/\r?\n/).map((line) => {
    const result = grammar.tokenizeLine(line, state);
    state = result.ruleStack;
    return { line, tokens: result.tokens };
  });
  const scopesAt = (lineText, text) => {
    const matching = tokensByLine.filter(({ line }) => line.includes(lineText));
    assert.equal(
      matching.length,
      1,
      `Expected one fixture line containing ${lineText}`,
    );
    const { line, tokens } = matching[0];
    const index = line.indexOf(text);
    assert.notEqual(index, -1);
    return tokens.find(
      (token) => token.startIndex <= index && token.endIndex > index,
    ).scopes;
  };
  assert.ok(
    scopesAt("Jenkins descriptor", "Jenkins").some((scope) =>
      scope.startsWith("comment."),
    ),
  );
  const call = scopesAt("contributor(", "contributor");
  assert.ok(!call.some((scope) => /^(comment|string)\./.test(scope)));
  const string = scopesAt("method(name:", "deploy");
  assert.ok(string.some((scope) => scope.startsWith("string.")));
  const key = scopesAt("property(name:", "name");
  assert.ok(!key.some((scope) => /^(comment|string)\./.test(scope)));
  const property = scopesAt("property(name:", "property");
  assert.ok(!property.some((scope) => /^(comment|string)\./.test(scope)));
});

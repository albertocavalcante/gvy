// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const textmate = require("vscode-textmate");
const oniguruma = require("vscode-oniguruma");

const root = path.resolve(__dirname, "../..");
const read = (file) => fs.readFileSync(path.join(root, file), "utf8");

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
  const lines = read("client/src/test/fixtures/jenkins-descriptor.gdsl").split(
    /\r?\n/,
  );
  let state = textmate.INITIAL;
  const scopesAt = (lineNumber, text) => {
    const line = lines[lineNumber];
    const index = line.indexOf(text);
    assert.notEqual(index, -1);
    const result = grammar.tokenizeLine(line, state);
    state = result.ruleStack;
    return result.tokens.find(
      (token) => token.startIndex <= index && token.endIndex > index,
    ).scopes;
  };
  assert.ok(
    scopesAt(0, "Jenkins").some((scope) => scope.startsWith("comment.")),
  );
  const call = scopesAt(1, "contributor");
  assert.ok(!call.some((scope) => /^(comment|string)\./.test(scope)));
  const string = scopesAt(2, "deploy");
  assert.ok(string.some((scope) => scope.startsWith("string.")));
  const key = scopesAt(3, "name");
  assert.ok(!key.some((scope) => /^(comment|string)\./.test(scope)));
  const property = scopesAt(3, "property");
  assert.ok(!property.some((scope) => /^(comment|string)\./.test(scope)));
});

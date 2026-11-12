// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import { ESLint, type Linter } from "eslint";
import ts from "typescript";

const floating = "@typescript-eslint/no-floating-promises";
const misused = "@typescript-eslint/no-misused-promises";

interface Fixture {
  name: string;
  body: string;
  rule?: string;
}

const fixtures: readonly Fixture[] = [
  {
    name: "bare Promise",
    body: "export function barePromise() { Promise.resolve(); }",
    rule: floating,
  },
  {
    name: "void Promise",
    body: "export function voidPromise() { void Promise.resolve(); }",
    rule: floating,
  },
  {
    name: "bare VS Code Thenable",
    body: 'export function bareThenable() { vscode.window.showInformationMessage("fixture"); }',
    rule: floating,
  },
  {
    name: "void VS Code Thenable",
    body: 'export function voidThenable() { void vscode.window.showInformationMessage("fixture"); }',
    rule: floating,
  },
  {
    name: "async callback where void is expected",
    body: "export function asyncVoidCallback() { acceptsVoid(async () => { await Promise.resolve(); }); }",
    rule: misused,
  },
  {
    name: "Thenable callback where void is expected",
    body: 'export function thenableVoidCallback() { acceptsVoid(() => vscode.window.showInformationMessage("fixture")); }',
    rule: misused,
  },
  {
    name: "awaited Promise and Thenable",
    body: 'export async function awaited() { await Promise.resolve(); await vscode.window.showInformationMessage("fixture"); }',
  },
  {
    name: "returned Promise",
    body: "export function returnedPromise() { return Promise.resolve(); }",
  },
  {
    name: "returned Thenable",
    body: 'export function returnedThenable() { return vscode.window.showInformationMessage("fixture"); }',
  },
  {
    name: "Promise with rejection handler",
    body: "export function handledPromise() { Promise.resolve().catch(reportError); }",
  },
  {
    name: "Thenable with rejection handler",
    body: 'export function handledThenable() { vscode.window.showInformationMessage("fixture").then(() => undefined, reportError); }',
  },
  {
    name: "void with an actual rejection handler",
    body: 'export function handledVoid() { void vscode.window.showInformationMessage("fixture").then(() => undefined, reportError); }',
  },
  {
    name: "callback with owned background rejection",
    body: "export function handledCallback() { acceptsVoid(() => { Promise.resolve().catch(reportError); }); }",
  },
  {
    name: "async callback with a Promise-aware owner",
    body: "export function ownedAsyncCallback() { acceptsAsync(async () => { await Promise.resolve(); }); }",
  },
];

describe("AI adapter promise lint policy", function () {
  this.timeout(20_000);

  const extensionRoot = path.resolve(__dirname, "../../../..");
  const aiRoot = path.join(extensionRoot, "client/src/features/ai");
  const files = fs
    .readdirSync(aiRoot, { recursive: true, encoding: "utf8" })
    .filter((name) => name.endsWith(".ts"))
    .sort()
    .map((name) => path.join(aiRoot, name));
  const header = [
    'import * as vscode from "vscode";',
    "declare function acceptsVoid(callback: () => void): void;",
    "declare function acceptsAsync(callback: () => Promise<void>): void;",
    "declare function reportError(error: unknown): void;",
  ];
  // Each fixture occupies exactly one line, making every diagnostic attributable.
  const code = [...header, ...fixtures.map((fixture) => fixture.body)].join(
    "\n",
  );
  const messages = new Map<string, readonly Linter.LintMessage[]>();

  before(async () => {
    assert.ok(
      files.length > 0,
      "The enforcement slice must contain source files",
    );
    const options: ts.CompilerOptions = {
      strict: true,
      noEmit: true,
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.Node20,
      moduleResolution: ts.ModuleResolutionKind.Node16,
      skipLibCheck: true,
      types: ["node"],
    };
    const host = ts.createCompilerHost(options);
    const read = host.readFile.bind(host);
    const key = (file: string) => host.getCanonicalFileName(path.resolve(file));
    const virtualFiles = new Set(files.map(key));
    host.readFile = (file) => (virtualFiles.has(key(file)) ? code : read(file));
    const program = ts.createProgram(files, options, host);
    for (const file of files) {
      assert.strictEqual(program.getSourceFile(file)?.text, code);
    }
    const diagnostics = ts.getPreEmitDiagnostics(program);
    assert.deepStrictEqual(
      diagnostics.map((item) =>
        ts.flattenDiagnosticMessageText(item.messageText, "\n"),
      ),
      [],
      "Fixtures must resolve real Promise and vscode types before linting",
    );

    // Load the real configuration, replacing only its disk-backed program with
    // typed fixtures. Rule selection, severity and options remain untouched.
    const eslint = new ESLint({
      cwd: extensionRoot,
      overrideConfig: {
        languageOptions: {
          parserOptions: { project: false, programs: [program] },
        },
      },
    });
    for (const file of files) {
      const results = await eslint.lintText(code, { filePath: file });
      assert.strictEqual(results.length, 1);
      const result = results[0];
      assert.ok(result);
      assert.ok(
        result.messages.every(
          (message) =>
            !message.fatal &&
            (message.ruleId === floating || message.ruleId === misused),
        ),
        JSON.stringify(result.messages),
      );
      messages.set(file, result.messages);
    }
  });

  for (const [index, fixture] of fixtures.entries()) {
    it(`${fixture.rule ? "rejects" : "accepts"} ${fixture.name} in every AI source file`, () => {
      for (const file of files) {
        const result = messages.get(file);
        assert.ok(result);
        const actual = result
          .filter((message) => message.line === header.length + index + 1)
          .map((message) => ({
            rule: message.ruleId,
            severity: message.severity,
          }));
        assert.deepStrictEqual(
          actual,
          fixture.rule ? [{ rule: fixture.rule, severity: 2 }] : [],
          path.relative(extensionRoot, file),
        );
      }
    });
  }
});

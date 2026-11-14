// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as fs from "fs/promises";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";

suite("Groovy file execution", () => {
  let directory: string;
  let outputDirectory: string | undefined;

  suiteSetup(async function () {
    this.timeout(120000);
    assert.ok(vscode.workspace.isTrusted, "The test workspace must be trusted");
    const extension = vscode.extensions.getExtension("albertocavalcante.gvy");
    assert.ok(extension, "Groovy extension must be installed");
    await extension.activate();
    directory = await fs.mkdtemp(
      path.join(os.tmpdir(), "gvy-editor-execution-"),
    );
  });

  suiteTeardown(async () => {
    await vscode.commands.executeCommand("workbench.action.closeAllEditors");
    if (outputDirectory)
      await fs.rm(outputDirectory, { recursive: true, force: true });
    if (directory) await fs.rm(directory, { recursive: true, force: true });
  });

  test("runs a saved standalone script in an owned process task", async function () {
    this.timeout(120000);
    const source = path.join(directory, "run.groovy");
    const marker = path.join(directory, "ran marker.txt");
    await fs.writeFile(
      source,
      `new File(${JSON.stringify(marker)}).text = 'ran'\n`,
    );
    const document = await vscode.workspace.openTextDocument(
      vscode.Uri.file(source),
    );
    await vscode.window.showTextDocument(document);
    const exits: number[] = [];
    const listener = vscode.tasks.onDidEndTaskProcess((event) => {
      if (event.execution.task.name.startsWith("Run · Standalone"))
        exits.push(event.exitCode ?? -1);
    });
    try {
      assert.strictEqual(
        await vscode.commands.executeCommand("groovy.runFile"),
        true,
      );
      assert.deepStrictEqual(exits, [0]);
      assert.strictEqual(await fs.readFile(marker, "utf8"), "ran");
    } finally {
      listener.dispose();
    }
  });

  test("System.exit does not stop the language server or a later run", async function () {
    this.timeout(120000);
    const source = path.join(directory, "ExitProbe.groovy");
    const laterSource = path.join(directory, "AfterExit.groovy");
    const marker = path.join(directory, "after exit marker.txt");
    await fs.writeFile(source, "System.exit(7)\n");
    await fs.writeFile(
      laterSource,
      `new File(${JSON.stringify(marker)}).text = 'alive'\n`,
    );
    const document = await vscode.workspace.openTextDocument(
      vscode.Uri.file(source),
    );
    await vscode.window.showTextDocument(document);
    const exits: number[] = [];
    const listener = vscode.tasks.onDidEndTaskProcess((event) => {
      if (event.execution.task.name.startsWith("Run · Standalone"))
        exits.push(event.exitCode ?? -1);
    });
    try {
      assert.strictEqual(
        await vscode.commands.executeCommand("groovy.runFile"),
        false,
      );
      assert.deepStrictEqual(exits, [7]);
      const deadline = Date.now() + 30000;
      let lenses: vscode.CodeLens[] | undefined;
      do {
        lenses = await vscode.commands.executeCommand<vscode.CodeLens[]>(
          "vscode.executeCodeLensProvider",
          document.uri,
        );
        if (lenses?.some((lens) => lens.command?.command === "groovy.runFile"))
          break;
        await new Promise((resolve) => setTimeout(resolve, 250));
      } while (Date.now() < deadline);
      assert.ok(
        lenses?.some((lens) => lens.command?.command === "groovy.runFile"),
        "The language server must answer a CodeLens request after System.exit",
      );
      const later = await vscode.workspace.openTextDocument(
        vscode.Uri.file(laterSource),
      );
      await vscode.window.showTextDocument(later);
      assert.strictEqual(
        await vscode.commands.executeCommand("groovy.runFile"),
        true,
      );
      assert.deepStrictEqual(exits, [7, 0]);
      assert.strictEqual(await fs.readFile(marker, "utf8"), "alive");
    } finally {
      listener.dispose();
    }
  });

  test("configured task and Run Again preserve profile and stdin", async function () {
    this.timeout(180000);
    const folder = path.join(directory, "profile-workspace");
    const resources = path.join(folder, "resources");
    const source = path.join(folder, "ProfileProbe.groovy");
    const started = path.join(folder, "started.txt");
    const result = path.join(folder, "result.txt");
    await fs.mkdir(path.join(folder, ".vscode"), { recursive: true });
    await fs.mkdir(resources, { recursive: true });
    await fs.writeFile(
      path.join(resources, "profile-resource.txt"),
      "resource-value",
    );
    await fs.writeFile(
      source,
      [
        `new File(${JSON.stringify(started)}).text = 'started'`,
        "def input = System.in.newReader().readLine()",
        "def resource = this.class.classLoader.getResourceAsStream('profile-resource.txt').text",
        `new File(${JSON.stringify(result)}).text = [args[0], args[1], input, System.getenv('GVY_PROFILE_TEST'), System.getProperty('gvy.profile.test'), resource].join('\\n')`,
      ].join("\n") + "\n",
    );
    await fs.writeFile(
      path.join(folder, ".vscode", "tasks.json"),
      JSON.stringify({
        version: "2.0.0",
        tasks: [
          {
            label: "GVY native profile",
            type: "groovy-file",
            file: "ProfileProbe.groovy",
            action: "run",
            context: "standalone",
            arguments: ["spaced argument", "$&;"],
            jvmArguments: ["-Xmx128m", "-Dgvy.profile.test=property-value"],
            workingDirectory: folder,
            environment: { GVY_PROFILE_TEST: "env-value" },
            classpath: [resources],
          },
        ],
      }),
    );
    const folderUri = vscode.Uri.file(folder);
    const originalCount = vscode.workspace.workspaceFolders?.length ?? 0;
    assert.ok(
      vscode.workspace.updateWorkspaceFolders(originalCount, 0, {
        uri: folderUri,
        name: "GVY native profile",
      }),
    );
    const processes = new Set<vscode.TaskExecution>();
    let outer: vscode.TaskExecution | undefined;
    const endEvents = new Set<vscode.TaskExecution>();
    const startListener = vscode.tasks.onDidStartTask((event) => {
      if (
        event.execution.task.name.startsWith("Run · Standalone") &&
        event.execution.task.name.includes(folder)
      )
        processes.add(event.execution);
    });
    const endListener = vscode.tasks.onDidEndTask((event) => {
      endEvents.add(event.execution);
      processes.delete(event.execution);
    });
    async function waitUntil(
      check: () => Promise<boolean>,
      message: string,
    ): Promise<void> {
      const deadline = Date.now() + 20000;
      while (Date.now() < deadline) {
        if (await check()) return;
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
      throw new Error(message);
    }
    async function exists(file: string): Promise<boolean> {
      try {
        await fs.access(file);
        return true;
      } catch {
        return false;
      }
    }
    async function supplyInput(value: string): Promise<void> {
      await waitUntil(
        () => exists(started),
        "The task did not reach its stdin read",
      );
      await waitUntil(async () => {
        const active = vscode.window.activeTerminal;
        return (
          !!active &&
          active.name.includes("Run · Standalone") &&
          active.name.includes(folder)
        );
      }, "The active terminal did not become the running Groovy task");
      const terminal = vscode.window.activeTerminal;
      assert.ok(
        terminal &&
          terminal.name.includes("Run · Standalone") &&
          terminal.name.includes(folder),
      );
      terminal.sendText(value, true);
      const expected = [
        "spaced argument",
        "$&;",
        value,
        "env-value",
        "property-value",
        "resource-value",
      ].join("\n");
      await waitUntil(async () => {
        try {
          return (await fs.readFile(result, "utf8")) === expected;
        } catch {
          return false;
        }
      }, "The Groovy program did not write the expected result");
    }
    try {
      await waitUntil(
        async () =>
          !!vscode.workspace.workspaceFolders?.some(
            (item) => item.uri.toString() === folderUri.toString(),
          ),
        "The temporary workspace folder did not open",
      );
      let configured: vscode.Task | undefined;
      await waitUntil(async () => {
        configured = (
          await vscode.tasks.fetchTasks({ type: "groovy-file" })
        ).find(
          (task) =>
            task.name === "GVY native profile" &&
            typeof task.scope === "object" &&
            task.scope.uri.toString() === folderUri.toString(),
        );
        return !!configured;
      }, "The configured groovy-file task was not resolved");
      outer = await vscode.tasks.executeTask(configured!);
      await supplyInput("first input");
      await waitUntil(
        async () => endEvents.has(outer!),
        "The configured task did not end",
      );
      await fs.rm(started);
      await fs.rm(result);
      const again = vscode.commands.executeCommand<boolean>(
        "groovy.runFileAgain",
      );
      await supplyInput("second input");
      assert.strictEqual(await again, true);
    } finally {
      if (outer && !endEvents.has(outer)) outer.terminate();
      for (const execution of processes) execution.terminate();
      try {
        await waitUntil(
          async () => (!outer || endEvents.has(outer)) && processes.size === 0,
          "Owned tasks did not terminate",
        );
      } finally {
        startListener.dispose();
        endListener.dispose();
        const index =
          vscode.workspace.workspaceFolders?.findIndex(
            (item) => item.uri.toString() === folderUri.toString(),
          ) ?? -1;
        if (index >= 0) {
          assert.ok(vscode.workspace.updateWorkspaceFolders(index, 1));
          await waitUntil(
            async () =>
              !vscode.workspace.workspaceFolders?.some(
                (item) => item.uri.toString() === folderUri.toString(),
              ),
            "The temporary workspace folder did not close",
          );
        }
      }
    }
  });

  test("compiles a saved file to managed bytecode", async function () {
    this.timeout(120000);
    const source = path.join(directory, "CompileProbe.groovy");
    await fs.writeFile(
      source,
      "class CompileProbe { static void main(String[] args) {} }\n",
    );
    const document = await vscode.workspace.openTextDocument(
      vscode.Uri.file(source),
    );
    await vscode.window.showTextDocument(document);
    const exits: number[] = [];
    const listener = vscode.tasks.onDidEndTaskProcess((event) => {
      if (!event.execution.task.name.startsWith("Compile · Standalone")) return;
      exits.push(event.exitCode ?? -1);
      const execution = event.execution.task.execution;
      if (execution instanceof vscode.ProcessExecution) {
        const index = execution.args.indexOf("-d");
        if (index >= 0) outputDirectory = execution.args[index + 1];
      }
    });
    try {
      assert.strictEqual(
        await vscode.commands.executeCommand("groovy.compileFile"),
        true,
      );
      assert.deepStrictEqual(exits, [0]);
      assert.ok(
        outputDirectory,
        "Compile process must report a managed output directory",
      );
      assert.ok(
        (
          await fs.stat(path.join(outputDirectory, "CompileProbe.class"))
        ).isFile(),
      );
    } finally {
      listener.dispose();
    }
  });
});

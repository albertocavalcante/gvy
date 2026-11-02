import * as assert from "assert";
import * as fs from "fs";
import * as path from "path";

const root = path.join(__dirname, "../../..");
const manifest = JSON.parse(
  fs.readFileSync(path.join(root, "package.json"), "utf8"),
);

function sourceFiles(dir: string): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      return entry.name === "test" ? [] : sourceFiles(full);
    }
    return entry.name.endsWith(".ts") ? [full] : [];
  });
}

const source = sourceFiles(path.join(root, "client/src"))
  .map((f) => fs.readFileSync(f, "utf8"))
  .join("\n");

describe("package.json manifest", () => {
  const declared: string[] = manifest.contributes.commands.map(
    (c: { command: string }) => c.command,
  );

  it("registers every contributed command", () => {
    for (const command of declared) {
      const registration = new RegExp(
        `registerCommand\\(\\s*"${command.replace(/\./g, "\\.")}"`,
      );
      assert.ok(
        registration.test(source),
        `${command} is contributed but never registered`,
      );
    }
  });

  it("only references contributed commands from menus and keybindings", () => {
    const menuCommands = Object.values(
      manifest.contributes.menus as Record<string, Array<{ command: string }>>,
    )
      .flat()
      .map((m) => m.command);
    const keyCommands = manifest.contributes.keybindings.map(
      (k: { command: string }) => k.command,
    );
    for (const command of [...menuCommands, ...keyCommands]) {
      assert.ok(declared.includes(command), `${command} is not contributed`);
    }
  });

  it("targets the VS Code version its @types/vscode describes", () => {
    const types = manifest.devDependencies["@types/vscode"].replace(/^\^/, "");
    assert.strictEqual(manifest.engines.vscode, `^${types}`);
  });
});

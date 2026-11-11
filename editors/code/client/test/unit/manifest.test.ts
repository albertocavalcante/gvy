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

  it("hides REPL commands: no server version implements groovy/execute", () => {
    const palette: Array<{ command: string; when?: string }> =
      manifest.contributes.menus.commandPalette ?? [];
    const repl = declared.filter((c) => c.startsWith("groovy.repl."));
    assert.ok(repl.length > 0);
    for (const command of repl) {
      assert.ok(
        palette.some((m) => m.command === command && m.when === "false"),
        `${command} is offered but cannot work`,
      );
    }
  });

  it("targets the VS Code version its @types/vscode describes", () => {
    const types = manifest.devDependencies["@types/vscode"].replace(/^\^/, "");
    assert.strictEqual(manifest.engines.vscode, `^${types}`);
  });

  it("treats every Jenkinsfile language file as a Jenkins file by default", () => {
    const language = manifest.contributes.languages.find(
      (l: { id: string }) => l.id === "jenkinsfile",
    );
    const defaults: string[] =
      manifest.contributes.configuration.properties[
        "groovy.jenkins.filePatterns"
      ].default;
    for (const pattern of [
      ...(language.filenames ?? []),
      ...(language.filenamePatterns ?? []),
    ]) {
      assert.ok(
        defaults.includes(pattern),
        `${pattern} opens as a Jenkinsfile but the server default omits it`,
      );
    }
  });

  it("uses the same Jenkins file patterns as the server default", () => {
    const kotlin = fs.readFileSync(
      path.join(
        root,
        "../../jenkins/src/main/kotlin/com/github/albertocavalcante/gvy/jenkins/JenkinsConfiguration.kt",
      ),
      "utf8",
    );
    const list = /DEFAULT_FILE_PATTERNS: List<String> = listOf\(([^)]*)\)/.exec(
      kotlin,
    );
    assert.ok(
      list,
      "DEFAULT_FILE_PATTERNS not found in JenkinsConfiguration.kt",
    );
    const serverDefaults = [...list[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
    assert.deepStrictEqual(
      manifest.contributes.configuration.properties[
        "groovy.jenkins.filePatterns"
      ].default,
      serverDefaults,
    );
  });

  it("keeps settings that run code or choose executables out of workspace control", () => {
    // A cloned repository's .vscode/settings.json must not be able to run its own code: GDSL scripts
    // run arbitrary Groovy in the server, the Java homes pick the java binary the extension and builds
    // launch, and VM args can add -javaagent. The AI tool switches decide whether language-server queries are
    // exposed to an LLM agent, which a repository must not be able to opt the user into. Gradle target
    // classpaths run one more Gradle operation, so a repository can't opt the user into that cost either.
    const properties = manifest.contributes.configuration.properties;
    for (const key of [
      "groovy.jenkins.gdslExecution.enabled",
      "groovy.jenkins.gdslPaths",
      "groovy.languageServer.javaHome",
      "groovy.java.home",
      "groovy.server.vmargs",
      "groovy.server.vmargsDefaults",
      "groovy.configuration.runtimes",
      "groovy.project.javaHome",
      "groovy.ai.tools.enabled",
      "groovy.ai.tools.allowed",
      "groovy.gradle.targetClasspaths.enabled",
    ]) {
      assert.ok(properties[key], `${key} is not contributed`);
      assert.ok(
        ["machine", "application"].includes(properties[key].scope),
        `${key} must be machine- or application-scoped, not ${properties[key].scope ?? "window"}`,
      );
    }
  });
});

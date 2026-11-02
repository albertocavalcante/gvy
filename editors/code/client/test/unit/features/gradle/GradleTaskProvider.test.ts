import * as assert from "assert";
import * as path from "path";
import proxyquire from "proxyquire";

class ShellExecution {
  constructor(
    public command: string,
    public args: string[],
    public options?: { cwd?: string },
  ) {}
}
class Task {
  group: unknown;
  constructor(
    public definition: { type: string; task: string },
    public scope: unknown,
    public name: string,
    public source: string,
    public execution: ShellExecution,
  ) {}
}

function load(existing: string[]) {
  const folder = {
    name: "w",
    uri: { fsPath: "/w", toString: () => "file:///w" },
  };
  const mod = proxyquire.noCallThru()(
    "../../../../src/features/gradle/GradleTaskProvider",
    {
      vscode: {
        workspace: { workspaceFolders: [folder] },
        Task,
        ShellExecution,
        TaskGroup: { Build: "build", Test: "test" },
        TaskScope: { Global: 1 },
      },
      fs: { existsSync: (p: string) => existing.includes(p) },
    },
  );
  return { mod, folder };
}

// The wrapper the provider looks for on the platform running the tests.
const WRAPPER =
  process.platform === "win32"
    ? path.win32.join("/w", "gradlew.bat")
    : "/w/gradlew";

describe("GradleTaskProvider", () => {
  const service = {
    getTasks: async () => [
      { name: "test", group: "verification", project: ":" },
    ],
  };

  it("runs the project's Gradle wrapper in the folder when there is one", async () => {
    const { mod } = load([WRAPPER]);
    const [task] = await new mod.GradleTaskProvider(service).provideTasks();
    assert.strictEqual(task.execution.command, WRAPPER);
    assert.deepStrictEqual(task.execution.args, ["test"]);
    assert.strictEqual(task.execution.options?.cwd, "/w");
  });

  it("falls back to gradle on PATH without a wrapper", async () => {
    const { mod } = load([]);
    const [task] = await new mod.GradleTaskProvider(service).provideTasks();
    assert.strictEqual(task.execution.command, "gradle");
  });

  it("resolves a task from tasks.json with the wrapper and folder cwd", () => {
    const { mod, folder } = load([WRAPPER]);
    const resolved = new mod.GradleTaskProvider(service).resolveTask(
      new Task(
        { type: "groovy-gradle", task: "build" },
        folder,
        "build",
        "groovy-gradle",
        new ShellExecution("", []),
      ),
    );
    assert.strictEqual(resolved.execution.command, WRAPPER);
    assert.strictEqual(resolved.execution.options?.cwd, "/w");
  });
});

describe("GradleTaskProvider task type and subprojects", () => {
  const resolve = (definition: Record<string, string>) => {
    const { mod, folder } = load([WRAPPER]);
    return new mod.GradleTaskProvider({ getTasks: async () => [] }).resolveTask(
      new Task(
        definition as { type: string; task: string },
        folder,
        definition.task,
        "groovy-gradle",
        new ShellExecution("", []),
      ),
    );
  };

  it("uses its own task type so it does not claim Gradle for Java's tasks", async () => {
    const { mod } = load([]);
    assert.strictEqual(mod.GRADLE_TASK_TYPE, "groovy-gradle");
    const provider = new mod.GradleTaskProvider({
      getTasks: async () => [{ name: "build", group: "build", project: ":" }],
    });
    const [task] = await provider.provideTasks();
    assert.strictEqual(task.definition.type, "groovy-gradle");
    assert.strictEqual(
      resolve({ type: "gradle", task: "build" }),
      undefined,
      "tasks of type gradle belong to the Gradle for Java extension",
    );
  });

  it("runs a subproject's task by its Gradle path", () => {
    assert.deepStrictEqual(
      resolve({ type: "groovy-gradle", task: "test", project: ":app" })
        .execution.args,
      [":app:test"],
    );
    assert.deepStrictEqual(
      resolve({ type: "groovy-gradle", task: "test", project: ":libs:core" })
        .execution.args,
      [":libs:core:test"],
    );
  });

  it("treats a project name (not a :path) as the root project", () => {
    // The server's project field is not pinned to Gradle paths; a root
    // project reported by name must not become :name:task.
    assert.deepStrictEqual(
      resolve({ type: "groovy-gradle", task: "test", project: "w" }).execution
        .args,
      ["test"],
    );
  });

  it("runs root-project tasks and full task paths unchanged", () => {
    for (const project of [undefined, "", ":"]) {
      const definition: Record<string, string> = {
        type: "groovy-gradle",
        task: "build",
      };
      if (project !== undefined) definition.project = project;
      assert.deepStrictEqual(resolve(definition).execution.args, ["build"]);
    }
    assert.deepStrictEqual(
      resolve({ type: "groovy-gradle", task: ":app:build", project: ":app" })
        .execution.args,
      [":app:build"],
    );
  });

  it("declares the task type and its properties in the manifest", () => {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const manifest = require("../../../../../package.json");
    const definition = manifest.contributes.taskDefinitions.find(
      (d: { type: string }) => d.type === "groovy-gradle",
    );
    assert.ok(definition, "groovy-gradle task definition");
    assert.deepStrictEqual(definition.required, ["task"]);
    assert.ok(definition.properties.project);
  });
});

describe("gradleExecutable", () => {
  it("prefers gradlew.bat on Windows", () => {
    const { mod } = load([]);
    const exists = (p: string) => p.endsWith("gradlew.bat");
    assert.strictEqual(
      mod.gradleExecutable("C:\\w", "win32", exists),
      "C:\\w\\gradlew.bat",
    );
  });
});

import * as assert from "assert";
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

describe("GradleTaskProvider", () => {
  const service = {
    getTasks: async () => [
      { name: "test", group: "verification", project: "w" },
    ],
  };

  it("runs the project's Gradle wrapper in the folder when there is one", async () => {
    const { mod } = load(["/w/gradlew"]);
    const [task] = await new mod.GradleTaskProvider(service).provideTasks();
    assert.strictEqual(task.execution.command, "/w/gradlew");
    assert.deepStrictEqual(task.execution.args, ["test"]);
    assert.strictEqual(task.execution.options?.cwd, "/w");
  });

  it("falls back to gradle on PATH without a wrapper", async () => {
    const { mod } = load([]);
    const [task] = await new mod.GradleTaskProvider(service).provideTasks();
    assert.strictEqual(task.execution.command, "gradle");
  });

  it("resolves a task from tasks.json with the wrapper and folder cwd", () => {
    const { mod, folder } = load(["/w/gradlew"]);
    const resolved = new mod.GradleTaskProvider(service).resolveTask(
      new Task(
        { type: "gradle", task: "build" },
        folder,
        "build",
        "gradle",
        new ShellExecution("", []),
      ),
    );
    assert.strictEqual(resolved.execution.command, "/w/gradlew");
    assert.strictEqual(resolved.execution.options?.cwd, "/w");
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

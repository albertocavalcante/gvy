# Run or compile a Groovy file

Open a saved Groovy file and select **Run File** from the editor play button, context menu, Command Palette, or Run
CodeLens. Use the play-button menu or **Groovy: Compile File** to produce bytecode without invoking the program.
Untitled files must be saved first. Execution requires a trusted workspace and Java.

| File                                    | Default context                                      |
| --------------------------------------- | ---------------------------------------------------- |
| Loose file or root-level scratch        | Standalone, with no project build                    |
| Observed member of a Gradle source set  | That target's incremental classes task and runtime   |
| Unclassified file in a nested directory | Choose Standalone or Inspect Gradle Project          |
| Multiple main classes or contexts       | Choose a labeled entry; gvy remembers it while valid |
| Class without a compatible main         | Compile File                                         |
| Gradle script or Jenkinsfile            | Use its host workflow                                |

Use **Groovy: Run File with Project…** when a scratch file needs project classes. A failed standalone run offers this
choice and never starts a project build automatically. **Groovy: Run File Again** repeats the last run, including a task
profile. **Groovy: Configure File Execution** opens the execution settings.

A root-level file included through a custom Gradle source configuration initially runs standalone when ownership has not
been observed. Select **Run File with Project…** to inspect the build and establish its project context.

Before a project run, gvy saves dirty documents in the build scope. A cancelled or failed save stops preparation.
Changes during preparation or compilation invalidate the result; run again after the edits are settled. Program stdin
and output are available in the process task terminal. Cancel the execution notification or terminate its task to stop
the owned process and prevent later stages.

## Runtime and output

Standalone execution uses an explicitly configured Groovy runtime, a configured local runtime, or the bundled Groovy
core 4.0.33 fallback. Ivy is bundled for configured `@Grab` behavior; add other Groovy modules to the classpath when
needed. Project execution requires an identifiable, nonconflicting project Groovy runtime. The language-server JAR is
never used to run your program, and gvy does not download an execution runtime.

Set `groovy.project.javaHome` for the project JDK, independently of `groovy.languageServer.javaHome`. Without a project
setting, gvy resolves the inherited `JAVA_HOME` or Java on `PATH`. The execution settings also allow an explicit Java
executable, runtime JARs, arguments, JVM arguments, environment, working directory and extra classpath entries. Keep
machine-specific paths in user or remote machine settings.

Run-only output directories are removed when the run ends. A successful Compile File result remains in managed storage;
use **Reveal Output** or **Groovy: Reveal File Compile Output** to inspect its bytecode. Failed or invalidated
compilation is not presented as a current artifact. Superseded owned results are removed.

## Repeatable task profiles

First Run needs no configuration file. For a repeatable configuration, add a task to `.vscode/tasks.json`:

```json
{
  "version": "2.0.0",
  "tasks": [
    {
      "label": "Run hello",
      "type": "groovy-file",
      "file": "hello.groovy",
      "action": "run",
      "context": "standalone",
      "arguments": ["one", "two words"],
      "jvmArguments": ["-Xmx256m"]
    }
  ]
}
```

Profiles also accept `workingDirectory`, `environment`, `classpath`, and `preparationTask`. Preparation tasks must be
process or shell tasks with a reportable exit status. Program environment overrides apply to compile/launch; automatic
Gradle preparation keeps its build environment and selected project JDK. A project `GRADLE_USER_HOME` override is
refused. Automatic Maven and custom-build discovery are not available; use an explicit standalone profile with the
required classpath and preparation task.

## Qualification limits

Windows Gradle preparation is unavailable pending native wrapper qualification. The Windows Java launcher must use a
compatible native encoding for Unicode arguments; gvy refuses those commands when the launcher would corrupt them. Long
non-ASCII Windows classpaths remain unqualified. Included or independent nested builds must be opened as their own
workspace or configured explicitly.

See the [design and capability matrix](../kb/FILE_EXECUTION.md) for current executed evidence and remaining gates.

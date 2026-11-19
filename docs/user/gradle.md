# Work with Gradle

**Prerequisites:** install gvy, open a trusted Gradle project folder, and provide a JDK compatible with that project's
Gradle version. A Gradle wrapper may download its distribution and project configuration can execute code; see
[private environments](private-environments.md).

For example, open a project containing `settings.gradle` and this `build.gradle`:

```groovy
plugins { id 'groovy' }
repositories { mavenCentral() }
dependencies { implementation localGroovy() }
```

Open `build.gradle`, wait for dependency resolution, and try completion in the file. The extension can also run
**Gradle: Build**, **Gradle: Test**, or **Gradle: Clean** from the Command Palette, using the project wrapper when
present. These commands execute the project's build, not just editor analysis.

Project configuration, network access, dependencies and JDK compatibility affect the result. An unresolved dependency
can leave language results incomplete; a successful task does not prove every language feature is correct. See
[Java](java.md) for JDK selection and [troubleshooting](troubleshooting.md) for failures. For task definitions and
development-source options, see the [commands](reference-commands.md) and [settings](reference-settings.md) references.

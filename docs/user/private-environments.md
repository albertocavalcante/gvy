# Private and restricted environments

**Prerequisites:** know your organization's repository, network, and credential policies and open only a project whose
build configuration you trust.

For example, before opening a private Gradle project, configure its normal repository credentials outside version
control using your organization's approved Gradle mechanism. Open the project, wait for dependency resolution, and
inspect the **Groovy Language Server** output if imports remain unresolved. Do not paste tokens or private repository
URLs into a public issue.

For a Gradle project, one possible repository configuration is:

```groovy
repositories {
    maven {
        url = uri('https://packages.example.com/maven')
        credentials {
            username = providers.gradleProperty('repoUser').orNull
            password = providers.gradleProperty('repoPassword').orNull
        }
    }
}
```

Supply `repoUser` and `repoPassword` through your organization's approved private Gradle properties mechanism, outside
the repository. Replace the example URL and verify access with the project's normal build. This example configures
Gradle; it does not establish authentication for gvy's separate Maven resolver.

Gradle resolution can run build configuration and download the wrapper distribution, plugins, and dependencies. A
project may use `mavenLocal()` if its Gradle build declares it; its contents are machine-specific. In development
source, the separate Maven resolver reads the local repository location from Maven settings and constructs remote
repositories including Maven Central and POM repositories. Do not assume it implements all Maven settings, mirrors, or
repository credentials: verify your actual setup. A successful Gradle build does not establish that the separate Maven
resolver can authenticate.

Development-source Jenkins metadata code can contact the Jenkins update center and plugin repository; extension update
checks can also contact release services. GDSL execution, when explicitly enabled in user or remote-machine settings,
runs arbitrary Groovy code. Cached dependencies do not establish complete offline operation, and internal mirrors or
downloads require local verification. The stable VSIX's bundled server differs from current source; see
[installation](../installation.md#stable-release-limitation).

Next, use [troubleshooting](troubleshooting.md) to capture a redacted failure and verify behavior with your
organization's network controls.

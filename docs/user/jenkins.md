# Edit Jenkins pipelines

**Prerequisites:** install gvy and open a trusted project folder. Create a file named `Jenkinsfile`:

```groovy
pipeline {
    agent any
    stages {
        stage('Build') {
            steps { echo 'Building' }
        }
    }
}
```

Open the file and request completion inside `steps`. The extension recognizes common Jenkinsfile patterns; available
results depend on the bundled metadata and server version. This does not connect to a Jenkins controller or validate a
pipeline against its installed plugins. Run your normal Jenkins validation separately.

For example, add a shared library in editor settings JSON, replacing the paths with existing absolute paths:

```json
{
  "groovy.jenkins.sharedLibraries": [
    {
      "name": "my-pipeline-lib",
      "jar": "/path/to/my-pipeline-lib.jar",
      "sourcesJar": "/path/to/my-pipeline-lib-sources.jar"
    }
  ]
}
```

The sources JAR is optional. The library can supply metadata for custom steps; results depend on its contents and server
version. See the [settings reference](reference-settings.md) and
[editor guide](../../editors/code/README.md#jenkins-pipeline-support). Development-source GDSL paths and execution are
user or remote-machine settings: execution is off by default and runs arbitrary Groovy code when enabled. Only enable it
for scripts you trust. See [private environments](private-environments.md) for network and trust considerations; use
[troubleshooting](troubleshooting.md) if results are missing.

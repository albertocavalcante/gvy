// Sample GDSL output from Jenkins /pipeline-syntax/gdsl endpoint
// This is a real example of what Jenkins generates

//The global script scope
def ctx = context(scope: scriptScope())
contributor(ctx) {
method(name: 'echo', type: 'Object', params: [message:'java.lang.String'], doc: 'Print Message')
method(name: 'error', type: 'Object', params: [message:'java.lang.String'], doc: 'Error signal')
method(name: 'isUnix', type: 'Object', params: [:], doc: 'Checks if running on a Unix-like node')
method(name: 'mail', type: 'Object', namedParams: [parameter(name: 'subject', type: 'java.lang.String'), parameter(name: 'body', type: 'java.lang.String'), parameter(name: 'bcc', type: 'java.lang.String'), parameter(name: 'cc', type: 'java.lang.String'), parameter(name: 'charset', type: 'java.lang.String'), parameter(name: 'from', type: 'java.lang.String'), parameter(name: 'mimeType', type: 'java.lang.String'), parameter(name: 'replyTo', type: 'java.lang.String'), parameter(name: 'to', type: 'java.lang.String'), ], doc: 'Mail')
method(name: 'parallel', type: 'Object', params: ['closures':'java.util.Map'], doc: 'Execute in parallel')
method(name: 'parallel', type: 'Object', namedParams: [parameter(name: 'closures', type: 'java.util.Map'), parameter(name: 'failFast', type: 'boolean'), ], doc: 'Execute in parallel')
method(name: 'timeout', type: 'Object', params: [body:Closure], namedParams: [parameter(name: 'time', type: 'int'), parameter(name: 'activity', type: 'boolean'), parameter(name: 'unit', type: 'java.util.concurrent.TimeUnit'), ], doc: 'Enforce time limit')
method(name: 'retry', type: 'Object', params: [count:'int', body:Closure], doc: 'Retry the body up to N times')
method(name: 'sleep', type: 'Object', params: [time:'int'], doc: 'Sleep')
method(name: 'sleep', type: 'Object', namedParams: [parameter(name: 'time', type: 'int'), parameter(name: 'unit', type: 'java.util.concurrent.TimeUnit'), ], doc: 'Sleep')
method(name: 'stage', type: 'Object', params: [name:'java.lang.String', body:Closure], doc: 'Stage')
method(name: 'stage', type: 'Object', params: [body:Closure], namedParams: [parameter(name: 'name', type: 'java.lang.String'), parameter(name: 'concurrency', type: 'java.lang.Integer'), ], doc: 'Stage')
method(name: 'input', type: 'Object', params: [message:'java.lang.String'], doc: 'Wait for interactive input')
method(name: 'input', type: 'Object', namedParams: [parameter(name: 'message', type: 'java.lang.String'), parameter(name: 'id', type: 'java.lang.String'), parameter(name: 'ok', type: 'java.lang.String'), parameter(name: 'parameters', type: 'java.util.List'), parameter(name: 'submitter', type: 'java.lang.String'), parameter(name: 'submitterParameter', type: 'java.lang.String'), ], doc: 'Wait for interactive input')
method(name: 'build', type: 'Object', params: [job:'java.lang.String'], doc: 'Build a job')
method(name: 'build', type: 'Object', namedParams: [parameter(name: 'job', type: 'java.lang.String'), parameter(name: 'parameters', type: 'java.util.List'), parameter(name: 'propagate', type: 'boolean'), parameter(name: 'quietPeriod', type: 'java.lang.Integer'), parameter(name: 'wait', type: 'boolean'), ], doc: 'Build a job')
method(name: 'properties', type: 'Object', params: [properties:'java.util.List'], doc: 'Set job properties')
method(name: 'catchError', type: 'Object', params: [body:Closure], namedParams: [parameter(name: 'buildResult', type: 'java.lang.String'), parameter(name: 'catchInterruptions', type: 'boolean'), parameter(name: 'message', type: 'java.lang.String'), parameter(name: 'stageResult', type: 'java.lang.String'), ], doc: 'Catch error and set build result to failure')
method(name: 'warnError', type: 'Object', params: [message:'java.lang.String', body:Closure], doc: 'Catch error and set build and stage result to unstable')
method(name: 'withEnv', type: 'Object', params: [overrides:'java.util.List', body:Closure], doc: 'Set environment variables')
method(name: 'node', type: 'Object', params: [label:'java.lang.String', body:Closure], doc: 'Allocate node')
method(name: 'ws', type: 'Object', params: [dir:'java.lang.String', body:Closure], doc: 'Allocate workspace')
property(name: 'env', type: 'org.jenkinsci.plugins.workflow.cps.EnvActionImpl')
property(name: 'params', type: 'org.jenkinsci.plugins.workflow.cps.ParamsVariable')
property(name: 'currentBuild', type: 'org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper')
property(name: 'scm', type: 'hudson.scm.SCM')
}
//Steps that require a node context
def nodeCtx = context(scope: closureScope())
contributor(nodeCtx) {
    def call = enclosingCall('node')
    if (call) {
method(name: 'sh', type: 'Object', params: [script:'java.lang.String'], doc: 'Shell Script')
method(name: 'sh', type: 'Object', namedParams: [parameter(name: 'script', type: 'java.lang.String'), parameter(name: 'encoding', type: 'java.lang.String'), parameter(name: 'label', type: 'java.lang.String'), parameter(name: 'returnStatus', type: 'boolean'), parameter(name: 'returnStdout', type: 'boolean'), ], doc: 'Shell Script')
method(name: 'bat', type: 'Object', params: [script:'java.lang.String'], doc: 'Windows Batch Script')
method(name: 'bat', type: 'Object', namedParams: [parameter(name: 'script', type: 'java.lang.String'), parameter(name: 'encoding', type: 'java.lang.String'), parameter(name: 'label', type: 'java.lang.String'), parameter(name: 'returnStatus', type: 'boolean'), parameter(name: 'returnStdout', type: 'boolean'), ], doc: 'Windows Batch Script')
method(name: 'powershell', type: 'Object', params: [script:'java.lang.String'], doc: 'PowerShell Script')
method(name: 'powershell', type: 'Object', namedParams: [parameter(name: 'script', type: 'java.lang.String'), parameter(name: 'encoding', type: 'java.lang.String'), parameter(name: 'label', type: 'java.lang.String'), parameter(name: 'returnStatus', type: 'boolean'), parameter(name: 'returnStdout', type: 'boolean'), ], doc: 'PowerShell Script')
method(name: 'pwsh', type: 'Object', params: [script:'java.lang.String'], doc: 'PowerShell Core Script')
method(name: 'dir', type: 'Object', params: [path:'java.lang.String', body:Closure], doc: 'Change current directory')
method(name: 'deleteDir', type: 'Object', params: [:], doc: 'Recursively delete the current directory from the workspace')
method(name: 'pwd', type: 'Object', params: [:], doc: 'Determine current directory')
method(name: 'pwd', type: 'Object', namedParams: [parameter(name: 'tmp', type: 'boolean'), ], doc: 'Determine current directory')
method(name: 'fileExists', type: 'Object', params: [file:'java.lang.String'], doc: 'Verify if file exists in workspace')
method(name: 'readFile', type: 'Object', params: [file:'java.lang.String'], doc: 'Read file from workspace')
method(name: 'readFile', type: 'Object', namedParams: [parameter(name: 'file', type: 'java.lang.String'), parameter(name: 'encoding', type: 'java.lang.String'), ], doc: 'Read file from workspace')
method(name: 'writeFile', type: 'Object', namedParams: [parameter(name: 'file', type: 'java.lang.String'), parameter(name: 'text', type: 'java.lang.String'), parameter(name: 'encoding', type: 'java.lang.String'), ], doc: 'Write file to workspace')
method(name: 'stash', type: 'Object', namedParams: [parameter(name: 'name', type: 'java.lang.String'), parameter(name: 'allowEmpty', type: 'boolean'), parameter(name: 'excludes', type: 'java.lang.String'), parameter(name: 'includes', type: 'java.lang.String'), parameter(name: 'useDefaultExcludes', type: 'boolean'), ], doc: 'Stash some files to be used later in the build')
method(name: 'unstash', type: 'Object', params: [name:'java.lang.String'], doc: 'Restore files previously stashed')
method(name: 'archiveArtifacts', type: 'Object', params: [artifacts:'java.lang.String'], doc: 'Archive the artifacts')
method(name: 'archiveArtifacts', type: 'Object', namedParams: [parameter(name: 'artifacts', type: 'java.lang.String'), parameter(name: 'allowEmptyArchive', type: 'boolean'), parameter(name: 'caseSensitive', type: 'boolean'), parameter(name: 'defaultExcludes', type: 'boolean'), parameter(name: 'excludes', type: 'java.lang.String'), parameter(name: 'fingerprint', type: 'boolean'), parameter(name: 'followSymlinks', type: 'boolean'), parameter(name: 'onlyIfSuccessful', type: 'boolean'), ], doc: 'Archive the artifacts')
method(name: 'junit', type: 'Object', params: [testResults:'java.lang.String'], doc: 'Archive JUnit-formatted test results')
method(name: 'junit', type: 'Object', namedParams: [parameter(name: 'testResults', type: 'java.lang.String'), parameter(name: 'allowEmptyResults', type: 'boolean'), parameter(name: 'checksName', type: 'java.lang.String'), parameter(name: 'healthScaleFactor', type: 'double'), parameter(name: 'keepLongStdio', type: 'boolean'), parameter(name: 'skipMarkingBuildUnstable', type: 'boolean'), parameter(name: 'skipOldReports', type: 'boolean'), parameter(name: 'skipPublishingChecks', type: 'boolean'), parameter(name: 'stdioRetention', type: 'java.lang.String'), parameter(name: 'testDataPublishers', type: 'java.util.List'), ], doc: 'Archive JUnit-formatted test results')
method(name: 'checkout', type: 'Object', params: [scm:'Map'], doc: 'Check out from version control')
method(name: 'checkout', type: 'Object', namedParams: [parameter(name: 'scm', type: 'Map'), parameter(name: 'changelog', type: 'boolean'), parameter(name: 'poll', type: 'boolean'), ], doc: 'Check out from version control')
    }
}

// Errors on:
// class org.jenkinsci.plugins.workflow.cps.steps.ParallelStep$ParallelLabelAction: null


package com.github.albertocavalcante.groovyjenkins.metadata

/**
 * Hardcoded definitions for stable Jenkins pipeline steps.
 *
 * These are core steps that haven't changed in years and are unlikely to change.
 * By hardcoding them, we provide:
 * - Immediate IntelliSense without network or classpath scanning
 * - Accurate parameter information
 * - Consistent experience across Jenkins versions
 *
 * Steps included here meet the criteria:
 * - Part of core workflow plugins (not third-party)
 * - Parameter signatures stable for 3+ years
 * - Widely used in production Jenkinsfiles
 *
 * @see JenkinsStepMetadata
 */
object StableStepDefinitions {

    private val STEPS: Map<String, JenkinsStepMetadata> = buildMap {
        // ========== workflow-durable-task-step plugin ==========
        put(
            "sh",
            JenkinsStepMetadata(
                name = "sh",
                plugin = "workflow-durable-task-step",
                documentation = "Execute a shell script on Unix-like systems.",
                parameters = mapOf(
                    "script" to StepParameter(
                        name = "script",
                        type = "String",
                        required = true,
                        documentation = "The shell script to execute.",
                    ),
                    "returnStdout" to StepParameter(
                        name = "returnStdout",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "If true, return the stdout as the step value.",
                    ),
                    "returnStatus" to StepParameter(
                        name = "returnStatus",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "If true, return the exit code as the step value.",
                    ),
                    "encoding" to StepParameter(
                        name = "encoding",
                        type = "String",
                        required = false,
                        documentation = "The encoding for the output (e.g., 'UTF-8').",
                    ),
                    "label" to StepParameter(
                        name = "label",
                        type = "String",
                        required = false,
                        documentation = "Label for the step in Blue Ocean and Pipeline Stage View.",
                    ),
                ),
            ),
        )

        put(
            "bat",
            JenkinsStepMetadata(
                name = "bat",
                plugin = "workflow-durable-task-step",
                documentation = "Execute a Windows batch script.",
                parameters = mapOf(
                    "script" to StepParameter(
                        name = "script",
                        type = "String",
                        required = true,
                        documentation = "The batch script to execute.",
                    ),
                    "returnStdout" to StepParameter(
                        name = "returnStdout",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "If true, return the stdout as the step value.",
                    ),
                    "returnStatus" to StepParameter(
                        name = "returnStatus",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "If true, return the exit code as the step value.",
                    ),
                    "encoding" to StepParameter(
                        name = "encoding",
                        type = "String",
                        required = false,
                        documentation = "The encoding for the output (e.g., 'UTF-8').",
                    ),
                    "label" to StepParameter(
                        name = "label",
                        type = "String",
                        required = false,
                        documentation = "Label for the step in Blue Ocean and Pipeline Stage View.",
                    ),
                ),
            ),
        )

        put(
            "powershell",
            JenkinsStepMetadata(
                name = "powershell",
                plugin = "workflow-durable-task-step",
                documentation = "Execute a PowerShell script.",
                parameters = mapOf(
                    "script" to StepParameter(
                        name = "script",
                        type = "String",
                        required = true,
                        documentation = "The PowerShell script to execute.",
                    ),
                    "returnStdout" to StepParameter(
                        name = "returnStdout",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "If true, return the stdout as the step value.",
                    ),
                    "returnStatus" to StepParameter(
                        name = "returnStatus",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "If true, return the exit code as the step value.",
                    ),
                    "encoding" to StepParameter(
                        name = "encoding",
                        type = "String",
                        required = false,
                        documentation = "The encoding for the output (e.g., 'UTF-8').",
                    ),
                    "label" to StepParameter(
                        name = "label",
                        type = "String",
                        required = false,
                        documentation = "Label for the step in Blue Ocean and Pipeline Stage View.",
                    ),
                ),
            ),
        )

        put(
            "pwsh",
            JenkinsStepMetadata(
                name = "pwsh",
                plugin = "workflow-durable-task-step",
                documentation = "Execute a PowerShell Core script (cross-platform).",
                parameters = mapOf(
                    "script" to StepParameter(
                        name = "script",
                        type = "String",
                        required = true,
                        documentation = "The PowerShell Core script to execute.",
                    ),
                    "returnStdout" to StepParameter(
                        name = "returnStdout",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "If true, return the stdout as the step value.",
                    ),
                    "returnStatus" to StepParameter(
                        name = "returnStatus",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "If true, return the exit code as the step value.",
                    ),
                    "encoding" to StepParameter(
                        name = "encoding",
                        type = "String",
                        required = false,
                        documentation = "The encoding for the output (e.g., 'UTF-8').",
                    ),
                    "label" to StepParameter(
                        name = "label",
                        type = "String",
                        required = false,
                        documentation = "Label for the step in Blue Ocean and Pipeline Stage View.",
                    ),
                ),
            ),
        )

        // ========== workflow-basic-steps plugin ==========
        put(
            "echo",
            JenkinsStepMetadata(
                name = "echo",
                plugin = "workflow-basic-steps",
                documentation = "Print a message to the console log.",
                parameters = mapOf(
                    "message" to StepParameter(
                        name = "message",
                        type = "String",
                        required = true,
                        documentation = "The message to print.",
                    ),
                ),
            ),
        )

        put(
            "error",
            JenkinsStepMetadata(
                name = "error",
                plugin = "workflow-basic-steps",
                documentation = "Signal an error and fail the build.",
                parameters = mapOf(
                    "message" to StepParameter(
                        name = "message",
                        type = "String",
                        required = true,
                        documentation = "The error message to display.",
                    ),
                ),
            ),
        )

        put(
            "timeout",
            JenkinsStepMetadata(
                name = "timeout",
                plugin = "workflow-basic-steps",
                documentation = "Enforce a time limit on a block of code.",
                parameters = mapOf(
                    "time" to StepParameter(
                        name = "time",
                        type = "int",
                        required = true,
                        documentation = "The time duration.",
                    ),
                    "unit" to StepParameter(
                        name = "unit",
                        type = "String",
                        required = false,
                        default = "MINUTES",
                        documentation = "Time unit: SECONDS, MINUTES, HOURS, DAYS.",
                    ),
                    "activity" to StepParameter(
                        name = "activity",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "If true, timeout resets on each new log output.",
                    ),
                ),
            ),
        )

        put(
            "retry",
            JenkinsStepMetadata(
                name = "retry",
                plugin = "workflow-basic-steps",
                documentation = "Retry a block of code a specified number of times.",
                parameters = mapOf(
                    "count" to StepParameter(
                        name = "count",
                        type = "int",
                        required = true,
                        documentation = "Maximum number of attempts.",
                    ),
                    "conditions" to StepParameter(
                        name = "conditions",
                        type = "List",
                        required = false,
                        documentation = "Conditions to check before retrying.",
                    ),
                ),
            ),
        )

        put(
            "sleep",
            JenkinsStepMetadata(
                name = "sleep",
                plugin = "workflow-basic-steps",
                documentation = "Pause execution for a specified time.",
                parameters = mapOf(
                    "time" to StepParameter(
                        name = "time",
                        type = "int",
                        required = true,
                        documentation = "The time to sleep.",
                    ),
                    "unit" to StepParameter(
                        name = "unit",
                        type = "String",
                        required = false,
                        default = "SECONDS",
                        documentation = "Time unit: SECONDS, MINUTES, HOURS, DAYS.",
                    ),
                ),
            ),
        )

        put(
            "dir",
            JenkinsStepMetadata(
                name = "dir",
                plugin = "workflow-basic-steps",
                documentation = "Change current directory for a block.",
                parameters = mapOf(
                    "path" to StepParameter(
                        name = "path",
                        type = "String",
                        required = true,
                        documentation = "The directory path to change to.",
                    ),
                ),
            ),
        )

        put(
            "deleteDir",
            JenkinsStepMetadata(
                name = "deleteDir",
                plugin = "workflow-basic-steps",
                documentation = "Delete the current directory and its contents.",
                parameters = emptyMap(),
            ),
        )

        put(
            "pwd",
            JenkinsStepMetadata(
                name = "pwd",
                plugin = "workflow-basic-steps",
                documentation = "Get the current working directory.",
                parameters = mapOf(
                    "tmp" to StepParameter(
                        name = "tmp",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "If true, return a temporary directory path.",
                    ),
                ),
            ),
        )

        put(
            "writeFile",
            JenkinsStepMetadata(
                name = "writeFile",
                plugin = "workflow-basic-steps",
                documentation = "Write content to a file in the workspace.",
                parameters = mapOf(
                    "file" to StepParameter(
                        name = "file",
                        type = "String",
                        required = true,
                        documentation = "The file path relative to workspace.",
                    ),
                    "text" to StepParameter(
                        name = "text",
                        type = "String",
                        required = true,
                        documentation = "The content to write.",
                    ),
                    "encoding" to StepParameter(
                        name = "encoding",
                        type = "String",
                        required = false,
                        documentation = "The file encoding (e.g., 'UTF-8').",
                    ),
                ),
            ),
        )

        put(
            "readFile",
            JenkinsStepMetadata(
                name = "readFile",
                plugin = "workflow-basic-steps",
                documentation = "Read content from a file in the workspace.",
                parameters = mapOf(
                    "file" to StepParameter(
                        name = "file",
                        type = "String",
                        required = true,
                        documentation = "The file path relative to workspace.",
                    ),
                    "encoding" to StepParameter(
                        name = "encoding",
                        type = "String",
                        required = false,
                        documentation = "The file encoding (e.g., 'UTF-8').",
                    ),
                ),
            ),
        )

        put(
            "fileExists",
            JenkinsStepMetadata(
                name = "fileExists",
                plugin = "workflow-basic-steps",
                documentation = "Check if a file exists in the workspace.",
                parameters = mapOf(
                    "file" to StepParameter(
                        name = "file",
                        type = "String",
                        required = true,
                        documentation = "The file path relative to workspace.",
                    ),
                ),
            ),
        )

        put(
            "isUnix",
            JenkinsStepMetadata(
                name = "isUnix",
                plugin = "workflow-basic-steps",
                documentation = "Check if running on a Unix-like system.",
                parameters = emptyMap(),
            ),
        )

        put(
            "stash",
            JenkinsStepMetadata(
                name = "stash",
                plugin = "workflow-basic-steps",
                documentation = "Stash files for later use in the build.",
                parameters = mapOf(
                    "name" to StepParameter(
                        name = "name",
                        type = "String",
                        required = true,
                        documentation = "A unique name for this stash.",
                    ),
                    "includes" to StepParameter(
                        name = "includes",
                        type = "String",
                        required = false,
                        documentation = "Ant-style pattern of files to include.",
                    ),
                    "excludes" to StepParameter(
                        name = "excludes",
                        type = "String",
                        required = false,
                        documentation = "Ant-style pattern of files to exclude.",
                    ),
                    "useDefaultExcludes" to StepParameter(
                        name = "useDefaultExcludes",
                        type = "boolean",
                        required = false,
                        default = "true",
                        documentation = "Whether to use default excludes (e.g., .git).",
                    ),
                    "allowEmpty" to StepParameter(
                        name = "allowEmpty",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "Allow stashing even if no files match.",
                    ),
                ),
            ),
        )

        put(
            "unstash",
            JenkinsStepMetadata(
                name = "unstash",
                plugin = "workflow-basic-steps",
                documentation = "Restore stashed files into the workspace.",
                parameters = mapOf(
                    "name" to StepParameter(
                        name = "name",
                        type = "String",
                        required = true,
                        documentation = "The name of the stash to restore.",
                    ),
                ),
            ),
        )

        put(
            "catchError",
            JenkinsStepMetadata(
                name = "catchError",
                plugin = "workflow-basic-steps",
                documentation = "Catch an error and set build/stage result instead of failing.",
                parameters = mapOf(
                    "buildResult" to StepParameter(
                        name = "buildResult",
                        type = "String",
                        required = false,
                        default = "FAILURE",
                        documentation = "Build result to set: SUCCESS, UNSTABLE, FAILURE, NOT_BUILT, ABORTED.",
                    ),
                    "stageResult" to StepParameter(
                        name = "stageResult",
                        type = "String",
                        required = false,
                        default = "FAILURE",
                        documentation = "Stage result to set: SUCCESS, UNSTABLE, FAILURE, NOT_BUILT, ABORTED.",
                    ),
                    "catchInterruptions" to StepParameter(
                        name = "catchInterruptions",
                        type = "boolean",
                        required = false,
                        default = "true",
                        documentation = "Whether to catch build interruptions (e.g., timeout, abort).",
                    ),
                    "message" to StepParameter(
                        name = "message",
                        type = "String",
                        required = false,
                        documentation = "A message to display when an error is caught.",
                    ),
                ),
            ),
        )

        put(
            "warnError",
            JenkinsStepMetadata(
                name = "warnError",
                plugin = "workflow-basic-steps",
                documentation = "Catch an error and set build result to UNSTABLE.",
                parameters = mapOf(
                    "message" to StepParameter(
                        name = "message",
                        type = "String",
                        required = true,
                        documentation = "A message to display when an error is caught.",
                    ),
                    "catchInterruptions" to StepParameter(
                        name = "catchInterruptions",
                        type = "boolean",
                        required = false,
                        default = "true",
                        documentation = "Whether to catch build interruptions.",
                    ),
                ),
            ),
        )

        put(
            "withEnv",
            JenkinsStepMetadata(
                name = "withEnv",
                plugin = "workflow-basic-steps",
                documentation = "Set environment variables for a block.",
                parameters = mapOf(
                    "overrides" to StepParameter(
                        name = "overrides",
                        type = "List<String>",
                        required = true,
                        documentation = "List of 'KEY=value' strings to set as environment variables.",
                    ),
                ),
            ),
        )

        put(
            "wrap",
            JenkinsStepMetadata(
                name = "wrap",
                plugin = "workflow-basic-steps",
                documentation = "Invoke a legacy build wrapper.",
                parameters = mapOf(
                    "delegate" to StepParameter(
                        name = "delegate",
                        type = "Map",
                        required = true,
                        documentation = "The build wrapper to invoke.",
                    ),
                ),
            ),
        )

        put(
            "unstable",
            JenkinsStepMetadata(
                name = "unstable",
                plugin = "workflow-basic-steps",
                documentation = "Set the build result to UNSTABLE.",
                parameters = mapOf(
                    "message" to StepParameter(
                        name = "message",
                        type = "String",
                        required = true,
                        documentation = "The message explaining why the build is unstable.",
                    ),
                ),
            ),
        )

        // ========== workflow-cps plugin ==========
        put(
            "parallel",
            JenkinsStepMetadata(
                name = "parallel",
                plugin = "workflow-cps",
                documentation = "Execute branches in parallel.",
                parameters = mapOf(
                    "branches" to StepParameter(
                        name = "branches",
                        type = "Map<String, Closure>",
                        required = true,
                        documentation = "Map of branch name to closure.",
                    ),
                    "failFast" to StepParameter(
                        name = "failFast",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "If true, abort other branches on first failure.",
                    ),
                ),
            ),
        )

        // ========== workflow-scm-step plugin ==========
        put(
            "checkout",
            JenkinsStepMetadata(
                name = "checkout",
                plugin = "workflow-scm-step",
                documentation = "Check out source code from SCM.",
                parameters = mapOf(
                    "scm" to StepParameter(
                        name = "scm",
                        type = "SCM",
                        required = true,
                        documentation = "The SCM configuration (e.g., git, svn).",
                    ),
                    "changelog" to StepParameter(
                        name = "changelog",
                        type = "boolean",
                        required = false,
                        default = "true",
                        documentation = "Whether to include changelog in build.",
                    ),
                    "poll" to StepParameter(
                        name = "poll",
                        type = "boolean",
                        required = false,
                        default = "true",
                        documentation = "Whether to poll for changes.",
                    ),
                ),
            ),
        )

        // ========== pipeline-stage-step plugin ==========
        put(
            "stage",
            JenkinsStepMetadata(
                name = "stage",
                plugin = "pipeline-stage-step",
                documentation = "Define a stage of the pipeline.",
                parameters = mapOf(
                    "name" to StepParameter(
                        name = "name",
                        type = "String",
                        required = true,
                        documentation = "The name of the stage.",
                    ),
                    "concurrency" to StepParameter(
                        name = "concurrency",
                        type = "int",
                        required = false,
                        documentation = "Maximum concurrent executions of this stage.",
                    ),
                ),
            ),
        )

        // ========== workflow-support plugin ==========
        put(
            "node",
            JenkinsStepMetadata(
                name = "node",
                plugin = "workflow-support",
                documentation = "Allocate a node (executor) for a block.",
                parameters = mapOf(
                    "label" to StepParameter(
                        name = "label",
                        type = "String",
                        required = false,
                        documentation = "Label expression to select the node.",
                    ),
                ),
            ),
        )

        put(
            "ws",
            JenkinsStepMetadata(
                name = "ws",
                plugin = "workflow-support",
                documentation = "Use a custom workspace directory.",
                parameters = mapOf(
                    "dir" to StepParameter(
                        name = "dir",
                        type = "String",
                        required = true,
                        documentation = "The workspace directory path.",
                    ),
                ),
            ),
        )

        // ========== pipeline-input-step plugin ==========
        put(
            "input",
            JenkinsStepMetadata(
                name = "input",
                plugin = "pipeline-input-step",
                documentation = "Wait for manual input before proceeding.",
                parameters = mapOf(
                    "message" to StepParameter(
                        name = "message",
                        type = "String",
                        required = true,
                        documentation = "The message to display.",
                    ),
                    "ok" to StepParameter(
                        name = "ok",
                        type = "String",
                        required = false,
                        documentation = "Text for the OK button.",
                    ),
                    "submitter" to StepParameter(
                        name = "submitter",
                        type = "String",
                        required = false,
                        documentation = "Comma-separated list of users/groups allowed to submit.",
                    ),
                    "submitterParameter" to StepParameter(
                        name = "submitterParameter",
                        type = "String",
                        required = false,
                        documentation = "Parameter name to store the submitter.",
                    ),
                    "parameters" to StepParameter(
                        name = "parameters",
                        type = "List",
                        required = false,
                        documentation = "List of parameters to request.",
                    ),
                    "id" to StepParameter(
                        name = "id",
                        type = "String",
                        required = false,
                        documentation = "Unique ID for this input step.",
                    ),
                ),
            ),
        )

        // ========== core-steps plugin (pipeline-build-step) ==========
        put(
            "build",
            JenkinsStepMetadata(
                name = "build",
                plugin = "pipeline-build-step",
                documentation = "Trigger another job/pipeline.",
                parameters = mapOf(
                    "job" to StepParameter(
                        name = "job",
                        type = "String",
                        required = true,
                        documentation = "The job name to build.",
                    ),
                    "parameters" to StepParameter(
                        name = "parameters",
                        type = "List",
                        required = false,
                        documentation = "Parameters to pass to the job.",
                    ),
                    "wait" to StepParameter(
                        name = "wait",
                        type = "boolean",
                        required = false,
                        default = "true",
                        documentation = "Whether to wait for the triggered job to complete.",
                    ),
                    "propagate" to StepParameter(
                        name = "propagate",
                        type = "boolean",
                        required = false,
                        default = "true",
                        documentation = "Whether to propagate the result of the triggered job.",
                    ),
                    "quietPeriod" to StepParameter(
                        name = "quietPeriod",
                        type = "int",
                        required = false,
                        documentation = "Quiet period in seconds before starting.",
                    ),
                ),
            ),
        )

        // ========== pipeline-utility-steps plugin ==========
        put(
            "readJSON",
            JenkinsStepMetadata(
                name = "readJSON",
                plugin = "pipeline-utility-steps",
                documentation = "Read JSON from file or text.",
                parameters = mapOf(
                    "file" to StepParameter(
                        name = "file",
                        type = "String",
                        required = false,
                        documentation = "Path to JSON file.",
                    ),
                    "text" to StepParameter(
                        name = "text",
                        type = "String",
                        required = false,
                        documentation = "JSON text to parse.",
                    ),
                    "returnPojo" to StepParameter(
                        name = "returnPojo",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "Return as POJO instead of JSONObject.",
                    ),
                ),
            ),
        )

        put(
            "writeJSON",
            JenkinsStepMetadata(
                name = "writeJSON",
                plugin = "pipeline-utility-steps",
                documentation = "Write data to JSON file.",
                parameters = mapOf(
                    "file" to StepParameter(
                        name = "file",
                        type = "String",
                        required = true,
                        documentation = "Path to output JSON file.",
                    ),
                    "json" to StepParameter(
                        name = "json",
                        type = "Object",
                        required = true,
                        documentation = "The object to serialize as JSON.",
                    ),
                    "pretty" to StepParameter(
                        name = "pretty",
                        type = "int",
                        required = false,
                        default = "0",
                        documentation = "Indentation level for pretty printing.",
                    ),
                ),
            ),
        )

        put(
            "readYaml",
            JenkinsStepMetadata(
                name = "readYaml",
                plugin = "pipeline-utility-steps",
                documentation = "Read YAML from file or text.",
                parameters = mapOf(
                    "file" to StepParameter(
                        name = "file",
                        type = "String",
                        required = false,
                        documentation = "Path to YAML file.",
                    ),
                    "text" to StepParameter(
                        name = "text",
                        type = "String",
                        required = false,
                        documentation = "YAML text to parse.",
                    ),
                ),
            ),
        )

        put(
            "writeYaml",
            JenkinsStepMetadata(
                name = "writeYaml",
                plugin = "pipeline-utility-steps",
                documentation = "Write data to YAML file.",
                parameters = mapOf(
                    "file" to StepParameter(
                        name = "file",
                        type = "String",
                        required = true,
                        documentation = "Path to output YAML file.",
                    ),
                    "data" to StepParameter(
                        name = "data",
                        type = "Object",
                        required = true,
                        documentation = "The object to serialize as YAML.",
                    ),
                    "overwrite" to StepParameter(
                        name = "overwrite",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "Whether to overwrite existing file.",
                    ),
                ),
            ),
        )

        // ========== junit plugin ==========
        put(
            "junit",
            JenkinsStepMetadata(
                name = "junit",
                plugin = "junit",
                documentation = "Archive JUnit-format test results.",
                parameters = mapOf(
                    "testResults" to StepParameter(
                        name = "testResults",
                        type = "String",
                        required = true,
                        documentation = "Ant-style pattern for test result files.",
                    ),
                    "allowEmptyResults" to StepParameter(
                        name = "allowEmptyResults",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "Allow no test results without failing.",
                    ),
                    "healthScaleFactor" to StepParameter(
                        name = "healthScaleFactor",
                        type = "double",
                        required = false,
                        documentation = "Factor for health report calculation.",
                    ),
                    "skipPublishingChecks" to StepParameter(
                        name = "skipPublishingChecks",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "Skip publishing to GitHub Checks.",
                    ),
                ),
            ),
        )

        // ========== workflow-multibranch (properties) ==========
        put(
            "properties",
            JenkinsStepMetadata(
                name = "properties",
                plugin = "workflow-multibranch",
                documentation = "Set job properties from within the pipeline.",
                parameters = mapOf(
                    "properties" to StepParameter(
                        name = "properties",
                        type = "List",
                        required = true,
                        documentation = "List of job properties to set.",
                    ),
                ),
            ),
        )

        // ========== core-archive-artifacts ==========
        put(
            "archiveArtifacts",
            JenkinsStepMetadata(
                name = "archiveArtifacts",
                plugin = "core",
                documentation = "Archive build artifacts.",
                parameters = mapOf(
                    "artifacts" to StepParameter(
                        name = "artifacts",
                        type = "String",
                        required = true,
                        documentation = "Ant-style pattern for files to archive.",
                    ),
                    "excludes" to StepParameter(
                        name = "excludes",
                        type = "String",
                        required = false,
                        documentation = "Ant-style pattern for files to exclude.",
                    ),
                    "allowEmptyArchive" to StepParameter(
                        name = "allowEmptyArchive",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "Allow no files to match without failing.",
                    ),
                    "fingerprint" to StepParameter(
                        name = "fingerprint",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "Fingerprint the archived files.",
                    ),
                    "onlyIfSuccessful" to StepParameter(
                        name = "onlyIfSuccessful",
                        type = "boolean",
                        required = false,
                        default = "false",
                        documentation = "Only archive if build is successful.",
                    ),
                    "caseSensitive" to StepParameter(
                        name = "caseSensitive",
                        type = "boolean",
                        required = false,
                        default = "true",
                        documentation = "Whether patterns are case-sensitive.",
                    ),
                    "followSymlinks" to StepParameter(
                        name = "followSymlinks",
                        type = "boolean",
                        required = false,
                        default = "true",
                        documentation = "Whether to follow symlinks.",
                    ),
                ),
            ),
        )
    }

    /**
     * Get a stable step definition by name.
     *
     * @param name The step name (e.g., "sh", "echo")
     * @return The step metadata, or null if not a stable step
     */
    fun getStep(name: String): JenkinsStepMetadata? = STEPS[name]

    /**
     * Check if a step is defined as stable.
     *
     * @param name The step name
     * @return true if this is a stable step
     */
    fun contains(name: String): Boolean = STEPS.containsKey(name)

    /**
     * Get all stable step definitions.
     *
     * @return Map of step name to metadata
     */
    fun all(): Map<String, JenkinsStepMetadata> = STEPS

    /**
     * Get the names of all stable steps.
     *
     * @return Set of step names
     */
    fun stepNames(): Set<String> = STEPS.keys
}

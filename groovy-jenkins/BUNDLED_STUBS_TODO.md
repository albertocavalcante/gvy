# TODO: Phase 0 - Bundled Jenkins Stubs Expansion

## Current Status

✅ **DONE**: Infrastructure for bundled Jenkins metadata ✅ **DONE**: Metadata loader using kotlinx.serialization ✅
**DONE**: Minimal stub with `sh` step only

## What's Missing

### Critical: Expand jenkins-stubs-metadata.json

Currently, only the `sh` step is included. Need to add **top 10-20 most common Jenkins plugins** with all their steps.

### Plugin Priority List (Based on Jenkins Plugin Index)

#### Phase 0.1: Core Pipeline Steps (MUST HAVE)

1. **workflow-basic-steps** - Core pipeline steps

   - [ ] `echo` - Print message
   - [ ] `error` - Signal error and fail build
   - [ ] `pwd` - Print working directory
   - [ ] `sleep` - Sleep for specified seconds
   - [ ] `timeout` - Enforce time limit
   - [ ] `waitUntil` - Wait for condition
   - [ ] `retry` - Retry block on failure
   - [ ] `readFile` - Read file from workspace
   - [ ] `writeFile` - Write file to workspace
   - [ ] `fileExists` - Check if file exists
   - [ ] `dir` - Change directory
   - [ ] `deleteDir` - Recursively delete directory
   - [ ] `isUnix` - Check if running on Unix

2. **workflow-durable-task-step** - Durable task steps

   - [x] `sh` - Shell script (DONE)
   - [ ] `bat` - Windows batch script
   - [ ] `powershell` - PowerShell script

3. **pipeline-model-definition** - Declarative pipeline
   - [ ] `pipeline` - Declarative pipeline root
   - [ ] `agent` - Specify where to run
   - [ ] `stages` - Container for stages
   - [ ] `stage` - Named stage
   - [ ] `steps` - Container for steps
   - [ ] `post` - Post-build actions
   - [ ] `environment` - Environment variables
   - [ ] `options` - Pipeline options
   - [ ] `parameters` - Build parameters
   - [ ] `triggers` - Build triggers
   - [ ] `tools` - Tool installations
   - [ ] `when` - Conditional execution

#### Phase 0.2: SCM & Version Control (HIGH PRIORITY)

4. **git** - Git SCM

   - [ ] `git` - Checkout from Git (Map-based: `url`, `branch`, `credentialsId`, `changelog`, `poll`)
   - [ ] `checkout` - General SCM checkout

5. **github** / **github-branch-source** - GitHub integration
   - [ ] GitHub-specific steps (if any)

#### Phase 0.3: Containers & Cloud (HIGH PRIORITY)

6. **docker-workflow** - Docker integration

   - [ ] `docker.image()` - Create Docker image DSL
   - [ ] `docker.build()` - Build Docker image
   - [ ] `docker.withRegistry()` - Use Docker registry
   - Global: `docker` object

7. **kubernetes** - Kubernetes plugin
   - [ ] `kubernetes` - Define pod template
   - [ ] `container` - Run in container
   - Global: `kubernetes` object

#### Phase 0.4: Credentials & Security (MEDIUM PRIORITY)

8. **credentials-binding** - Bind credentials

   - [ ] `withCredentials` - Bind credentials to variables
   - [ ] `usernamePassword` - Username/password credential
   - [ ] `string` - Secret text credential
   - [ ] `file` - Secret file credential
   - [ ] `sshUserPrivateKey` - SSH private key

9. **ssh-agent** - SSH agent
   - [ ] `sshagent` - Run with SSH agent

#### Phase 0.5: Notifications (MEDIUM PRIORITY)

10. **email-ext** - Extended email

    - [ ] `emailext` - Send enhanced email

11. **slack** (optional) - Slack notifications
    - [ ] `slackSend` - Send Slack message

#### Phase 0.6: Testing & Reporting (MEDIUM PRIORITY)

12. **junit** - JUnit test results

    - [ ] `junit` - Publish JUnit test results

13. **timestamper** - Console timestamps
    - [ ] `timestamps` - Wrap with timestamps

### Global Variables to Add

Current: `env`, `params`, `currentBuild`

TODO:

- [ ] `docker` - Docker DSL (from docker-workflow)
- [ ] `kubernetes` - Kubernetes DSL (from kubernetes plugin)
- [ ] `scm` - SCM information
- [ ] `manager` - Build manager (from groovy-postbuild)

### Implementation Approach

**Option A: Manual JSON Authoring** (Quick, for Phase 0)

- Manually write step definitions in `jenkins-stubs-metadata.json`
- Copy parameter info from Jenkins documentation
- Pros: Quick to implement, full control
- Cons: Tedious, error-prone, hard to maintain

**Option B: Generate from Real Jenkins** (Better, for future)

- HACK: Use simple script to extract from local Jenkins instance
- TODO: Implement `jenkins-metadata-dumper.groovy` (Phase 2.5)
- For now, manually author based on jenkins.io documentation

**Recommended for PR #1**: Use Option A with top 5 most critical plugins only

- Keep PR small and reviewable
- Expand in subsequent PRs

### Files to Update

1. **jenkins-stubs-metadata.json** - Add step definitions

   - Currently: 1 step (`sh`)
   - Target for Phase 0: 30-50 steps from top 10 plugins
   - Final goal: 100+ steps from top 20 plugins

2. **Tests** - Add test cases for new steps
   - Test metadata loading for each plugin
   - Test parameter extraction
   - Test required vs optional parameters

### Validation Checklist

Before considering Phase 0 complete:

- [ ] All top 10 plugins represented
- [ ] Each plugin has its most common steps
- [ ] Each step has accurate parameter metadata
- [ ] Required parameters marked correctly
- [ ] Default values specified where applicable
- [ ] Documentation strings present
- [ ] Tests pass for all steps
- [ ] JSON is valid and loads successfully

### Future Enhancements (Out of Scope for Phase 0)

- FIXME: Generate stubs from actual plugin JARs using ASM
- FIXME: Auto-generate documentation from JavaDoc
- TODO: Support for nested step parameters (e.g., `modernSCM` within `library`)
- TODO: Support for step overloads (different parameter combinations)
- HACK: Currently using static JSON; consider plugin metadata API in future

### Related Issues

- See `PARSER_REFACTORING_PLAN.md` for ASM-based parameter extraction
- Approved plan is the internal doc "calm-wandering-lollipop" (ask maintainers for access)
- Phase 2.5 will add controller metadata dump for instance-specific accuracy

### PR Breakdown

**PR #1** (Current): Infrastructure + `sh` step ✅ **PR #2**: Add top 5 core plugins (workflow-basic-steps,
workflow-durable-task-step, pipeline-model-definition, git, docker-workflow) **PR #3**: Add remaining plugins
(credentials-binding, kubernetes, junit, etc.) **PR #4**: Beta testing with real Jenkinsfiles, refinements

---

**Last Updated**: 2025-12-03 **Status**: PR #1 complete, ready for review

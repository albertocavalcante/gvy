# AGENTS.md

<github-cli-ironclad>
  MUST use GitHub CLI exclusively for reading GitHub content. NEVER use WebFetch for GitHub.

# Repository metadata

gh api repos/owner/repo --jq '.description, .topics, .homepage' gh api repos/owner/repo/releases/latest --jq '.tag_name,
.published_at'

# File content (base64 decode)

gh api repos/owner/repo/contents/path/to/file.md --jq '.content' | base64 -d gh api repos/owner/repo/contents/README.md
--jq '.content' | base64 -d

# Search and exploration

gh api repos/owner/repo/contents/path --jq '.[] | select(.type=="file") | .name' gh search repos "gradle hooks
language:kotlin" --limit 5 gh search repos --language=kotlin --topic=gradle --limit 5

# Raw content with --jq for processing

gh api repos/owner/repo/contents/build.gradle.kts --jq '.download_url' | xargs curl -s </github-cli-ironclad>

<git-workflow-rules>
  <forbidden>
    NEVER commit while on main branch
  </forbidden>

  <required-workflow>
    0. git branch --show-current (verify not on main)
    1. git checkout -b new-branch-name
    2. git add file1 file2 (specific files only)
    3. git commit -m "type: description"
    4. git push -u origin new-branch-name
    5. gh pr create
  </required-workflow>

  <git-add-rules>
    NEVER: git add . or git add -A or git add -u
    ALWAYS: git add path/to/specific/file
  </git-add-rules>

  <commit-format>
    Use conventional commits: type: description
    Examples: feat:, fix:, ci:, docs:, refactor:
  </commit-format>

  <safety-check>
    Always run: git branch --show-current
    If on main: immediately git checkout -b new-branch
  </safety-check>

  <ship-it-definition>
    When user says "ship it": execute full workflow above and open PR
    Expected delivery: Pull Request created and ready for review
  </ship-it-definition>
</git-workflow-rules>

<local-git-ignore>
  File: .git/info/exclude (local ignore, not shared with others)

Add files/folders that should be ignored only on your machine: echo "filename.ext" >> .git/info/exclude echo
"folder-name/" >> .git/info/exclude

Use for: local dev files, personal notes, temp directories NOT for: files all developers should ignore (use .gitignore
instead)

  <default-behavior>
    When asked to "ignore a file" or "add to ignore": ALWAYS use .git/info/exclude
    Only use .gitignore when explicitly told "add to .gitignore"
  </default-behavior>
</local-git-ignore>

<test-debugging>
  For test debugging with println: MUST run with --info flag
  Example: ./gradlew test --tests "*SomeTest*" --console=plain --info
  Without --info flag, println output will not be visible in test results
</test-debugging>

<code-quality>
  ./gradlew lint - Check for issues (no changes)
  ./gradlew lintFix - Auto-fix all correctable lint and formatting issues
  Order: Spotless formatting first, then Detekt auto-correct
</code-quality>

<pr-review-commands>
  SonarCloud API - Get code quality issues for PR analysis:
  curl "https://sonarcloud.io/api/issues/search?componentKeys=albertocavalcante_groovy-lsp&pullRequest=PR_NUMBER&types=BUG,CODE_SMELL,VULNERABILITY&statuses=OPEN,CONFIRMED"

GitHub CLI - PR review insights: gh pr view PR_NUMBER --comments # View PR with all comments gh api
repos/owner/repo/pulls/PR_NUMBER/reviews | jq -r '.[] | "Author: \(.user.login)\nState: \(.state)\n\(.body)"' gh api
repos/owner/repo/pulls/PR_NUMBER/comments | jq -r '.[] | "File: \(.path)\nLine: \(.line)\nAuthor:
\(.user.login)\nComment:\n\(.body)"' gh pr checks PR_NUMBER # Check CI/CD status

Claude WebFetch - For automated analysis: WebFetch url="https://sonarcloud.io/api/issues/search?..." prompt="List the
issues with severity and file locations" </pr-review-commands>

<github-issues>
  # Quick Issue Creation
  gh issue create -R albertocavalcante/groovy-lsp \
    --title "[lsp/completion] Add method signatures" \
    --body-file github-issues/issue.md \
    --label "enhancement" --label "lsp/completion" --label "P1-must" --label "size/M"

# Label Formula: Type + Area + Priority + Size

Type: bug, enhancement, documentation, architecture, tech-debt Area: lsp/completion, lsp/navigation, lsp/diagnostics,
lsp/hover, lsp/symbols Priority: P0-critical, P1-must, P2-should, P3-nice Size: size/XS, size/S, size/M, size/L, size/XL

# Common Commands

gh issue list -R albertocavalcante/groovy-lsp -l "P1-must" gh issue edit 31 --add-label "blocked" --remove-label
"help-wanted" gh label create "lsp/completion" -c "c2e0c6" -d "Completion features" </github-issues>

<lsp-component-testing>
# Testing LSP Components Individually

For debugging LSP components when unit tests fail, create temporary Kotlin files to test specific functionality:

```bash
# Write temporary test file
Write(/tmp/TestComponent.kt)
  import com.github.albertocavalcante.groovylsp.util.GroovyBuiltinMethods
  fun main() {
      println("Testing component...")
      println("Result: ${GroovyBuiltinMethods.isBuiltinMethod("println")}")
  }

# Compile and run against the built jar
kotlinc -cp "build/libs/groovy-lsp-0.1.0-SNAPSHOT.jar" /tmp/TestComponent.kt -include-runtime -d /tmp/TestComponent.jar
java -cp "/tmp/TestComponent.jar:build/libs/groovy-lsp-0.1.0-SNAPSHOT.jar" TestComponentKt

# Use for: util classes, AST analysis, hover providers, compilation services
```

This pattern allows testing individual components in isolation without the complexity of full test suite setup.
</lsp-component-testing>

<test-debugging-with-println>
# Debug Pattern for Test Failures

When debugging test failures with println statements for immediate visibility:

```bash
# Add println statements to code for debugging
println("DEBUG: variable = $value")
println("DEBUG: Processing context '$contextName' with ${nodes.size} nodes")

# Run tests with output redirection to capture debug messages
./gradlew test --tests "*TestName*" --console=plain --info 2>&1 | grep -E "(DEBUG:|FAILED|ERROR)"

# Alternative: Filter for specific debug patterns
./gradlew test --tests "*WorkspaceTest*" --console=plain --info 2>&1 | grep -E "(DEBUG: Found|DEBUG: Processing|AssertionFailedError)"

# Pattern for targeted debugging
./gradlew test --tests "*SpecificTest*" --console=plain --info 2>&1 | grep -E "(DEBUG: HoverProvider|DEBUG: Node.*found|Should provide hover)"
```

**Usage notes:**

- Use println() for immediate debug output visibility in tests (logger.debug() won't show)
- Always redirect with `2>&1 | grep -E "(pattern)"` to capture output
- Remove println statements after debugging is complete
- Use targeted grep patterns to filter relevant debug information
- Combine with `--info` flag for detailed test output

**Example debug workflow:**

1. Add println statements to investigate failing functionality
2. Run tests with output redirection and grep filtering
3. Analyze debug output to identify root cause
4. Fix the issue based on debug findings
5. Remove debug println statements and verify tests still pass </test-debugging-with-println>

<lsp-server-debugging>
# Live LSP Server Debugging

For testing the LSP server directly to debug runtime issues, dependency conflicts, or service errors:

```bash
# Create test file to trigger analysis
echo 'class TestClass {}' > /path/to/test.groovy

# Test server initialization with timeout and error filtering
echo '{"method":"initialize","params":{"rootUri":"file:///path/to/workspace","capabilities":{}}}' | \
  timeout 5s java -jar build/libs/groovy-lsp-0.1.0-SNAPSHOT.jar 2>&1 | \
  grep -E "(visitClassEx|MissingMethodException|CodeNarc|ERROR)" || echo "No errors found"

# Test specific error patterns
echo '{"method":"initialize","params":{"rootUri":"file:///path/to/workspace","capabilities":{}}}' | \
  timeout 10s java -jar build/libs/groovy-lsp-0.1.0-SNAPSHOT.jar 2>&1 | \
  grep -E "(Exception|Error|WARN.*failed)"
```

**Usage notes:**

- Use timeout to prevent hanging on server startup issues
- Pipe stderr to stdout with `2>&1` to capture all error output
- Use specific grep patterns to filter for known error types
- Test with real workspace directories to trigger dependency resolution
- Create minimal test files to isolate specific analysis features

**Common error patterns to grep for:**

- `visitClassEx|MissingMethodException` - CodeNarc version conflicts
- `VirtualResults|UnsupportedOperationException.*virtual results` - CodeNarc plugin issues
- `ClassNotFoundException|NoClassDefFoundError` - Dependency issues
- `Exception.*groovy` - Groovy compilation problems
- `WARN.*CodeNarc` - CodeNarc analysis failures

**Testing with real workspace files:**

```bash
# Test with actual problematic file instead of dummy files
echo '{"method":"initialize","params":{"rootUri":"file:///path/to/real/workspace","capabilities":{}}}' | \
  timeout 10s java -jar build/libs/groovy-lsp-0.1.0-SNAPSHOT.jar 2>&1 | \
  grep -E "(VirtualResults|MockClosure\.groovy|specific-error-pattern)"

# Example: Testing JenkinsPipelineUnit workspace that had VirtualResults issues
echo '{"method":"initialize","params":{"rootUri":"file:///Users/albertocavalcante/dev/workspace/JenkinsPipelineUnit","capabilities":{}}}' | \
  timeout 10s java -jar build/libs/groovy-lsp-0.1.0-SNAPSHOT.jar 2>&1 | \
  grep -E "(VirtualResults|MockClosure\.groovy)" || echo "✅ No VirtualResults errors"
```

</lsp-server-debugging>

<vscode-extension-development>
# VSCode Extension Development Workflow

Complete workflow for updating the Groovy LSP server and deploying changes to VSCode extension.

## 1. Build Updated LSP Server

```bash
# Build LSP JAR (skip detekt for faster iteration)
./gradlew shadowJar -x detekt

# Alternative: Full build with quality checks
./gradlew build
```

## 2. Update VSCode Extension with New LSP JAR

```bash
# Copy updated JAR to extension server directory
cp build/libs/groovy-lsp-0.1.0-SNAPSHOT.jar /path/to/vscode-groovy/server/groovy-lsp.jar

# Navigate to extension directory
cd /path/to/vscode-groovy

# Package extension with updated server
npm run package
```

## 3. Install/Update Extension in VSCode

```bash
# Install or force-update the extension
code --install-extension vscode-groovy-0.1.0.vsix --force

# Alternative: Install from specific path
code --install-extension /full/path/to/vscode-groovy-0.1.0.vsix --force
```

## 4. Refresh LSP Server

**For LSP capability changes (new features):**

- Restart VSCode completely (Cmd+Q/Ctrl+Q and reopen)
- Server capabilities are negotiated during initialization

**For LSP implementation changes (bug fixes):**

- Use "Groovy: Restart Language Server" command
- Or reload window (Cmd+R/Ctrl+R)

## 5. Development Tips

**Quick JAR refresh without extension rebuild:**

```bash
# For development iteration - just replace JAR and restart LSP
cp build/libs/groovy-lsp-0.1.0-SNAPSHOT.jar /path/to/vscode-groovy/server/groovy-lsp.jar
# Then use "Groovy: Restart Language Server" in VSCode
```

**Verify extension installation:**

```bash
# List installed extensions
code --list-extensions | grep groovy

# Check extension details
code --list-extensions --show-versions | grep groovy
```

**Debug LSP communication:**

- Enable "groovy.traceServer": "verbose" in VSCode settings
- Check "Groovy Language Server Trace" output channel
- Use VSCode Developer Tools (Help > Toggle Developer Tools)

## 6. Testing Checklist

- [ ] LSP server starts without errors
- [ ] Basic features work (hover, completion, diagnostics)
- [ ] New features are available (folding ranges, code actions, etc.)
- [ ] No regression in existing functionality
- [ ] Check output channels for error messages

**Common Issues:**

- JAR not updated: Verify file timestamps match
- Capabilities not available: Full VSCode restart required
- Extension not loading: Check VSCode Extensions view for errors
- LSP crashes: Check "Groovy Language Server" output channel </vscode-extension-development>

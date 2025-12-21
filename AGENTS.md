# AGENTS.md

<project-context>
  This is a Kotlin/JVM project: Groovy Language Server (LSP).
  Primary language: Kotlin
  Build tool: Gradle (Kotlin DSL)
  Test framework: JUnit 5 with Kotlin
</project-context>

<github-cli-ironclad>
  MUST use GitHub CLI exclusively for reading GitHub content. NEVER use WebFetch for GitHub.

  # Repository metadata
  gh api repos/owner/repo --jq '.description, .topics, .homepage'
  gh api repos/owner/repo/releases/latest --jq '.tag_name, .published_at'

  # File content (base64 decode)
  gh api repos/owner/repo/contents/path/to/file.md --jq '.content' | base64 -d
  gh api repos/owner/repo/contents/README.md --jq '.content' | base64 -d

  # Search and exploration
  gh api repos/owner/repo/contents/path --jq '.[] | select(.type=="file") | .name'
  gh search repos "gradle hooks language:kotlin" --limit 5
  gh search repos --language=kotlin --topic=gradle --limit 5

  # Raw content with --jq for processing
  gh api repos/owner/repo/contents/build.gradle.kts --jq '.download_url' | xargs curl -s
</github-cli-ironclad>

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

  <pr-title-format>
    Pull Request titles MUST also use conventional style: "type: description" (e.g., feat: add Jenkins metadata scaffold)
  </pr-title-format>

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

  Add files/folders that should be ignored only on your machine:
    echo "filename.ext" >> .git/info/exclude
    echo "folder-name/" >> .git/info/exclude

  Use for: local dev files, personal notes, temp directories
  NOT for: files all developers should ignore (use .gitignore instead)

  <default-behavior>
    When asked to "ignore a file" or "add to ignore": ALWAYS use .git/info/exclude
    Only use .gitignore when explicitly told "add to .gitignore"
  </default-behavior>
</local-git-ignore>

<privacy>
  NEVER expose absolute home directory paths in docs, specs, commits, or PRs. Use `$HOME` or `~` instead.
</privacy>

<engineering-notes>
  <heuristics-and-tradeoffs>
    When introducing ANY heuristic (regex parsing, string matching on error messages, token-length widening, line-based
    fallbacks, etc.), ALWAYS annotate the code with an explicit tag:
    - `NOTE:` explain the trade-off and why we’re doing it now
    - `TODO:` describe the deterministic/robust approach we’d prefer long-term (what would remove the heuristic)
    - `FIXME:` if the heuristic is known to be flaky/incorrect in some cases
    - `HACK:` only if it’s a last-resort workaround (and must include a clear exit plan)

    The goal is to make heuristics visible, reviewable, and easy to revisit over time.
  </heuristics-and-tradeoffs>
</engineering-notes>

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

  GitHub CLI - PR review insights:
  gh pr view PR_NUMBER --comments
  gh api repos/owner/repo/pulls/PR_NUMBER/reviews | jq -r '.[] | "Author: \(.user.login)\nState: \(.state)\n\(.body)"'
  gh api repos/owner/repo/pulls/PR_NUMBER/comments | jq -r '.[] | "File: \(.path)\nLine: \(.line)\nAuthor: \(.user.login)\nComment:\n\(.body)"'
  gh pr checks PR_NUMBER
</pr-review-commands>

<pr-feedback-workflow>
  When addressing review feedback, retrieve all sources before coding (run from repo root):
  - General PR comments: gh pr view PR_NUMBER --json comments --jq '.comments[] | {author: .author.login, body: .body, createdAt: .createdAt}'
  - Inline review comments: gh api repos/albertocavalcante/groovy-lsp/pulls/PR_NUMBER/comments --jq '.[] | {author: .user.login, path: .path, line: .line, body: .body}'
  - Review summaries: gh pr view PR_NUMBER --json reviews --jq '.reviews[] | {author: .author.login, state: .state, body: .body, submittedAt: .submittedAt}'
  Fetch these before making changes so no feedback is missed.
</pr-feedback-workflow>

<github-issues>
  # Quick Issue Creation
  gh issue create -R albertocavalcante/groovy-lsp \
    --title "[lsp/completion] Add method signatures" \
    --body-file github-issues/issue.md \
    --label "enhancement" --label "lsp/completion" --label "P1-must" --label "size/M"

  # Label Formula: Type + Area + Priority + Size
  Type: bug, enhancement, documentation, architecture, tech-debt
  Area: lsp/completion, lsp/navigation, lsp/diagnostics, lsp/hover, lsp/symbols
  Priority: P0-critical, P1-must, P2-should, P3-nice
  Size: size/XS, size/S, size/M, size/L, size/XL

  # Common Commands
  gh label create "lsp/completion" -c "c2e0c6" -d "Completion features"
</github-issues>

<flaky-tests>
  When a test fails due to a timeout or non-deterministic behavior, add a FIXME comment to the test file indicating it is flaky.
  Example: // FIXME: Flaky test due to timeout
</flaky-tests>

<test-naming>
  ALWAYS use backtick style with descriptive sentences for test names.
  NEVER use camelCase for test function names.

  Examples:
  ✅ Good:  @Test fun `FixContext stores diagnostic correctly`()
  ✅ Good:  @Property fun `property - unregistered random rule names return null handler`()
  ❌ Bad:   @Test fun fixContextStoresDiagnosticCorrectly()
  ❌ Bad:   @Property fun nonCodeNarcDiagnosticsReturnEmptyActions()

  Rationale: Backtick style with spaces is more readable, self-documenting, and follows BDD principles.
  Applies to ALL test types: unit tests, integration tests, and property-based tests.
</test-naming>

<test-driven-development>
  Follow TDD (Test-Driven Development) for new features. Recommended 99.999% of the time.
  
  <tdd-cycle>
    1. **Red**: Write a failing test first that defines expected behavior
    2. **Green**: Write minimum code to make the test pass
    3. **Refactor**: Clean up while keeping tests green
  </tdd-cycle>

  <order-of-operations>
    1. Create test file with test cases (they may fail to compile initially)
    2. Create minimal interface/class to make tests compile
    3. Run tests - verify they fail for the right reason
    4. Implement until tests pass
    5. Refactor if needed
  </order-of-operations>

  <when-to-skip>
    TDD can be skipped when overhead is excessive (rare cases):
    - Pure infrastructure/wiring code with no logic
    - Exploratory spikes that will be rewritten
    - Trivial one-liner changes
    
    When skipping, write tests immediately after implementation.
  </when-to-skip>

  <rationale>
    TDD ensures testable design, living documentation, and confidence in refactoring.
    Tests written after implementation often miss edge cases and test implementation details.
  </rationale>
</test-driven-development>

<github-actions>
  <action-pinning>
    ALWAYS pin GitHub Actions to full SHA commit hash, NOT version tags.
    Add an inline comment with the full semver version for readability.

    ✅ Good:
    uses: actions/checkout@8e8c483db84b4bee98b60c0593521ed34d9990e8 # v6.0.1
    uses: actions/setup-java@f2beeb24e141e01a676f977032f5a29d81c9e27e # v5.1.0

    ❌ Bad:
    uses: actions/checkout@v4
    uses: actions/setup-java@v4

    Rationale: SHA pinning prevents supply chain attacks where a tag could be moved
    to point to malicious code. The inline version comment maintains readability.

    To find SHA for a version:
    gh api repos/OWNER/REPO/git/refs/tags/VERSION --jq '.object.sha'
    Example: gh api repos/actions/checkout/git/refs/tags/v6.0.1 --jq '.object.sha'
  </action-pinning>
</github-actions>

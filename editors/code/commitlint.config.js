// Commitlint configuration for conventional commits
// Install with: npm install --save-dev @commitlint/cli @commitlint/config-conventional
// Then add to package.json husky hook: "commit-msg": "commitlint --edit $1"

module.exports = {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'type-enum': [
      2,
      'always',
      [
        'feat',     // New feature → minor version bump
        'fix',      // Bug fix → patch version bump
        'docs',     // Documentation changes
        'style',    // Code style changes (no code changes)
        'refactor', // Code refactoring (no features/fixes)
        'perf',     // Performance improvements
        'test',     // Adding tests
        'build',    // Build system changes
        'ci',       // CI configuration changes
        'chore',    // Maintenance tasks
        'revert'    // Revert commit
      ]
    ],
    'subject-case': [2, 'never', ['start-case', 'pascal-case', 'upper-case']],
    'subject-full-stop': [2, 'never', '.'],
    'subject-max-length': [2, 'always', 72],
    'body-leading-blank': [2, 'always'],
    'body-max-line-length': [2, 'always', 100],
    'footer-leading-blank': [2, 'always'],
    'footer-max-line-length': [2, 'always', 100]
  }
};

// Example commit messages:
// feat: add status bar indicator for LSP server state
// fix: resolve Java path detection on Windows
// docs: update installation instructions
// chore: update dependencies to latest versions
// feat!: refactor extension API (breaking change)
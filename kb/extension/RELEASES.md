# Release Strategy

This document explains how releases work in this project using
[release-please](https://github.com/googleapis/release-please).

## Overview

We use a **branch-based release strategy** with two release tracks:

| Branch  | Release Type | Versions                         | Purpose                |
| ------- | ------------ | -------------------------------- | ---------------------- |
| `main`  | Stable       | `0.1.0`, `0.1.1`, `0.2.0`        | Production releases    |
| `alpha` | Prerelease   | `0.1.0-alpha.1`, `0.1.0-alpha.2` | Testing & early access |

## How It Works

### Automatic Releases

1. **Commit with conventional commits** (e.g., `feat:`, `fix:`, `BREAKING CHANGE:`)
2. **Release-please creates a PR** with version bump and changelog
3. **Merge the PR** → GitHub release is created automatically

### Stable Releases (main branch)

**Workflow:** `.github/workflows/release-please.yml`

```bash
# Work on main branch
git checkout main
git commit -m "feat: add new feature"
git push

# Release-please creates PR: "chore(main): release 0.1.0"
# Merge PR → v0.1.0 released
```

**Version bumps:**

- `fix:` → patch (0.1.0 → 0.1.1)
- `feat:` → minor (0.1.0 → 0.2.0)
- `BREAKING CHANGE:` → major (0.1.0 → 1.0.0)

### Alpha Releases (alpha branch)

**Workflow:** `.github/workflows/release-alpha.yml`

```bash
# Work on alpha branch
git checkout alpha
git commit -m "feat: experimental feature"
git push

# Release-please creates PR: "chore(alpha): release 0.1.0-alpha.1"
# Merge PR → v0.1.0-alpha.1 released
```

**Version bumps:**

- `fix:` → 0.1.0-alpha.1 → 0.1.0-alpha.2
- `feat:` → 0.1.0-alpha.1 → 0.2.0-alpha.1
- `BREAKING CHANGE:` → 0.1.0-alpha.1 → 1.0.0-alpha.1

### Promoting Alpha to Stable

When alpha is ready for stable release:

```bash
# Merge alpha into main
git checkout main
git merge alpha
git push

# Release-please creates PR: "chore(main): release 0.1.0"
# Merge PR → v0.1.0 released (stable)
```

## Manual Version Control

### Release-As Commit Footer

Force a specific version by adding `Release-As:` to commit message:

```bash
git commit -m "feat: major rewrite

Release-As: 2.0.0"
```

**Use cases:**

- Force a specific version
- Skip versions
- Create one-off prereleases

**Examples:**

```bash
# Force stable version
Release-As: 1.0.0

# Force alpha version
Release-As: 1.0.0-alpha.1

# Force beta version
Release-As: 1.0.0-beta.1

# Force rc version
Release-As: 1.0.0-rc.1
```

## Configuration Files

### Main Branch (Stable)

- **Config:** `release-please-config.json`
- **Manifest:** `.release-please-manifest.json`
- **Workflow:** `.github/workflows/release-please.yml`

```json
{
  "release-type": "node",
  "packages": {
    ".": {
      "prerelease": false
    }
  }
}
```

### Alpha Branch (Prerelease)

- **Config:** `.github/release-please-config-alpha.json`
- **Manifest:** `.github/.release-please-manifest-alpha.json`
- **Workflow:** `.github/workflows/release-alpha.yml`

```json
{
  "release-type": "node",
  "packages": {
    ".": {
      "prerelease": true,
      "prerelease-type": "alpha"
    }
  }
}
```

## Conventional Commits

Release-please analyzes commit messages to determine version bumps:

| Type                    | Example                        | Bump  |
| ----------------------- | ------------------------------ | ----- |
| `fix:`                  | `fix: resolve null pointer`    | Patch |
| `feat:`                 | `feat: add dark mode`          | Minor |
| `BREAKING CHANGE:`      | `feat!: remove deprecated API` | Major |
| `chore:`, `docs:`, etc. | `docs: update README`          | None  |

**Format:**

```
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

**Examples:**

```bash
# Patch bump (0.1.0 → 0.1.1)
git commit -m "fix: resolve memory leak in status bar"

# Minor bump (0.1.0 → 0.2.0)
git commit -m "feat: add Jenkins pipeline support"

# Major bump (0.1.0 → 1.0.0)
git commit -m "feat!: redesign configuration API

BREAKING CHANGE: Config format changed from JSON to YAML"

# No bump
git commit -m "docs: update installation guide"
```

## Workflow Details

### Main Branch Workflow

**Trigger:** Push to `main`

**Steps:**

1. Analyzes commits since last release
2. Determines version bump
3. Updates `CHANGELOG.md`
4. Updates `package.json` version
5. Creates release PR
6. On merge: creates GitHub release + tag

### Alpha Branch Workflow

**Trigger:** Push to `alpha`

**Steps:** Same as main, but:

- Uses alpha config
- Generates alpha versions
- Marks releases as "prerelease" on GitHub

## FAQ

### How do I create the first alpha release?

```bash
git checkout -b alpha
git commit -m "feat: initial alpha

Release-As: 0.1.0-alpha.1"
git push -u origin alpha
```

### How do I skip a version?

Use `Release-As:` footer:

```bash
git commit -m "feat: major update

Release-As: 2.0.0"
```

### Can I have beta or rc releases?

Yes! Use `Release-As:`:

```bash
# Beta
git commit -m "feat: beta feature

Release-As: 1.0.0-beta.1"

# Release Candidate
git commit -m "feat: rc feature

Release-As: 1.0.0-rc.1"
```

Or create a `beta` branch with its own workflow.

### What if I want to change the prerelease type?

Edit `.github/release-please-config-alpha.json`:

```json
{
  "prerelease-type": "beta"  // or "rc", "alpha", etc.
}
```

## References

- [Release Please Documentation](https://github.com/googleapis/release-please)
- [Conventional Commits](https://www.conventionalcommits.org/)
- [Semantic Versioning](https://semver.org/)

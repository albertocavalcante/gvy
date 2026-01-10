# Bazel Build System

This project uses **Gradle as the primary build system** and **Bazel as an experimental alternative**. Both systems are
maintained in parallel to allow gradual migration and comparison.

## Quick Start

```bash
# Build everything
make bazel-build

# Run tests
make bazel-test

# Build the deployable JAR
make bazel-jar

# Compare Gradle and Bazel outputs
make verify-builds
```

## Prerequisites

- Bazel 7.x or higher
- Java 21+ (same as Gradle requirements)

Install Bazel:

```bash
# macOS
brew install bazel

# Linux/WSL
# See https://bazel.build/install
```

## Key Commands

| Command                            | Description                     |
| ---------------------------------- | ------------------------------- |
| `bazel build //...`                | Build all targets               |
| `bazel test //...`                 | Run all tests                   |
| `bazel build //gls:gls_deploy.jar` | Build fat JAR                   |
| `bazel run @maven//:pin`           | Update Maven lock file          |
| `bazel clean --expunge`            | Full clean (removes all caches) |
| `bazel query //...`                | List all targets                |
| `bazel query 'deps(//gls:gls)'`    | Show dependencies               |

## Project Structure

Each module has a `BUILD.bazel` file defining its targets:

- `kt_library` - Kotlin library target
- `kt_jvm_binary` - Kotlin binary target
- `java_binary` - Java binary target (for deploy JARs)

Example:

```bash
groovy-lsp/
├── BUILD.bazel           # Defines groovy-lsp-lib, gls, gls_deploy
├── src/main/kotlin/...
└── src/test/kotlin/...
```

## Adding a New Module

1. Create `BUILD.bazel` in the module directory:

```python
load("//tools/build_defs:kotlin.bzl", "kt_library")

kt_library(
    name = "my-module",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    visibility = ["//visibility:public"],
    deps = [
        # Dependencies here
    ],
)
```

2. Add to dependent modules' `deps` list:

```python
deps = [
    "//my-module",
    # ...
]
```

## Managing Maven Dependencies

Dependencies are defined in `MODULE.bazel` and locked in `maven_install.json`.

### Adding a dependency:

1. Edit `MODULE.bazel`, add to `maven.install(artifacts = [...])`:

```python
"com.example:library:1.0.0",
```

2. Update the lock file:

```bash
make bazel-sync
# or
bazel run @maven//:pin
```

3. Reference in `BUILD.bazel`:

```python
deps = [
    "@maven//:com_example_library",
]
```

Maven coordinates are transformed: `com.example:library` → `@maven//:com_example_library`

## Configuration

### `.bazelrc`

Global config in `.bazelrc` (checked into git).

### `user.bazelrc`

Local overrides in `user.bazelrc` (gitignored). Example:

```bash
# Use more cores
build --jobs=8

# Enable remote cache
build --remote_cache=...
```

## Troubleshooting

### "No such target"

```bash
# List available targets
bazel query //...
```

### "Cannot find Maven dependency"

```bash
# Re-pin Maven dependencies
make bazel-sync
```

### Stale cache issues

```bash
# Full clean
make bazel-clean
```

### Build fails but Gradle works

The Bazel setup is experimental. Check:

1. All modules have `BUILD.bazel` files
2. Dependencies are declared correctly
3. `maven_install.json` is up to date

## IDE Support

### IntelliJ IDEA

Install the Bazel plugin from JetBrains Marketplace:

1. Settings → Plugins → Search "Bazel"
2. Import project as Bazel project
3. Select `MODULE.bazel` as root

## Differences from Gradle

| Aspect      | Gradle                      | Bazel                        |
| ----------- | --------------------------- | ---------------------------- |
| Config      | `build.gradle.kts`          | `BUILD.bazel`                |
| Deps        | `gradle/libs.versions.toml` | `MODULE.bazel`               |
| Output      | `build/libs/*.jar`          | `bazel-bin/groovy-lsp/*.jar` |
| Cache       | `.gradle/`                  | `bazel-*`                    |
| Incremental | Yes                         | Yes (more aggressive)        |

## Why Bazel?

- **Faster incremental builds** - Fine-grained caching
- **Reproducible builds** - Hermetic build environment
- **Monorepo support** - Better multi-language support (Kotlin + TypeScript)
- **Remote execution** - Can distribute builds (future)

## Current Limitations

- Some modules don't have `BUILD.bazel` files yet
- Test coverage not yet configured
- Linting config incomplete
- Not used in CI/CD (Gradle is authoritative)

---

## Disk Space Management with Git Worktrees

### The Problem

When using Git worktrees, Bazel creates a separate `output_base` directory for each worktree, leading to massive disk
usage:

- Each worktree path generates a unique output_base (MD5 hash of workspace path)
- Location: `/private/var/tmp/_bazel_<user>/<hash>/`
- Typical size: 1-5 GB per worktree
- With 20+ worktrees, this can consume 20-100 GB of disk space

### Current Mitigations (Already Configured)

The `.bazelrc` file already includes these optimizations:

#### 1. Shared Repository Cache

```bash
common --repository_cache=~/.cache/bazel-repo
common --experimental_repository_cache_hardlinks
```

- **What it does**: Shares downloaded external dependencies (Maven JARs, HTTP archives) across all worktrees
- **Disk savings**: ~90% reduction for external deps (uses hardlinks instead of copies)
- **Current size**: ~326 MB (shared across all worktrees)
- **Safe for**: Concurrent builds across multiple worktrees

#### 2. Shared Disk Cache

```bash
common --disk_cache=~/.cache/bazel
```

- **What it does**: Shares build action outputs (compiled classes, JARs) across worktrees
- **Disk savings**: 50-80% reduction for build artifacts
- **Status**: Currently configured but appears empty (needs investigation)
- **Safe for**: Concurrent builds

### Additional Solutions

#### Option A: Fix Disk Cache (RECOMMENDED)

The disk_cache is configured but not being populated. Verify it's working:

```bash
# Check if disk cache is being used
bazel info | grep disk
du -sh ~/.cache/bazel

# If empty, the cache may not be working. Try:
bazel clean
bazel build //...
du -sh ~/.cache/bazel  # Should show significant size
```

#### Option B: Enable Automatic Garbage Collection (Bazel 7.4+)

**Note**: You're using Bazel 9.0.0rc3, which supports these flags.

Add to `.bazelrc`:

```bash
# Auto-clean disk cache when idle
common --experimental_disk_cache_gc_max_size=10G
common --experimental_disk_cache_gc_max_age=30d
common --experimental_disk_cache_gc_idle_delay=300s
```

Or create `user.bazelrc` for local overrides:

```bash
cp user.bazelrc.template user.bazelrc
# Add flags above
```

#### Option C: Clean Orphaned Output Bases (RECOMMENDED)

Use the provided cleanup script to remove output_base directories from deleted worktrees:

```bash
# Run the cleanup script
./tools/bazel-cleanup.sh

# Or manually:
# 1. List all output_base directories and their workspaces
for dir in /private/var/tmp/_bazel_$USER/*/; do
  if [ -f "$dir/README" ]; then
    echo "=== $dir ==="
    head -1 "$dir/README"
  fi
done

# 2. Remove orphaned directories (where workspace no longer exists)
# WARNING: This will delete build outputs!
# Only run after verifying the workspace is truly gone
rm -rf /private/var/tmp/_bazel_$USER/<hash>
```

#### Option D: Remote Cache (Future Enhancement)

For teams or CI/CD, consider a local remote cache:

**Using bazel-remote (Docker)**:

```bash
# Start local cache server
docker run -d \
  -v ~/.cache/bazel-remote:/data \
  -p 9090:8080 \
  buchgr/bazel-remote-cache \
  --max_size=20

# Configure in user.bazelrc
build --remote_cache=http://localhost:9090
build --remote_upload_local_results=true
build --remote_timeout=3600
```

**Benefits**:

- Single cache shared across all worktrees
- Better cache hit rates
- Can be shared across machines
- Built-in garbage collection

#### Option E: Shared output_base (NOT RECOMMENDED)

**DO NOT** use the same `--output_base` for multiple worktrees:

- Concurrent builds will serialize (one at a time) due to file locking
- Defeats the purpose of worktrees (parallel development)
- Can cause cache corruption

**Only use separate output_base per worktree**.

### Best Practices for Worktree Usage

1. **Enable automatic GC** - Add GC flags to `.bazelrc`
2. **Run cleanup script monthly** - Remove orphaned output_base directories
3. **Monitor disk usage**:
   ```bash
   du -sh /private/var/tmp/_bazel_$USER/*/ | sort -h
   du -sh ~/.cache/bazel*
   ```
4. **Use `bazel clean`** before deleting a worktree:
   ```bash
   cd /path/to/worktree
   bazel clean --expunge  # Removes output_base
   cd ..
   rm -rf worktree/
   ```

### Disk Usage Reference

| Location                                 | Purpose                                               | Typical Size        | Shared?                     |
| ---------------------------------------- | ----------------------------------------------------- | ------------------- | --------------------------- |
| `/private/var/tmp/_bazel_<user>/<hash>/` | Build outputs, action cache, external deps (unpacked) | 1-5 GB per worktree | No (one per workspace path) |
| `~/.cache/bazel-repo`                    | Downloaded external deps (archives)                   | 200-500 MB          | Yes (across all worktrees)  |
| `~/.cache/bazel`                         | Build action outputs (disk_cache)                     | 1-5 GB              | Yes (across all worktrees)  |
| `~/.cache/bazel-disk`                    | Alternative disk cache location                       | 2 GB                | Yes                         |

### References

- [Bazel Output Directory Layout](https://bazel.build/remote/output-directories)
- [Remote Caching Guide](https://bazel.build/remote/caching)
- [Disk Cache GC Issue](https://github.com/bazelbuild/bazel/issues/5139)
- [Git Worktree Discussion](https://groups.google.com/g/bazel-discuss/c/dS9UQOK5bec)
- [bazel-remote on GitHub](https://github.com/buchgr/bazel-remote)

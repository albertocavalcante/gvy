# GMM (Gradle Module Metadata) Support for Bazel - Handoff Document

## Goal

Enable Compose Multiplatform Desktop in Bazel using Coursier's GMM support for correct platform variant selection.

## Current Status: ✅ GMM WORKING

All patches applied successfully. Maven resolution with GMM variant selection is working.

---

## What's Working

### 1. Coursier GMM Upgrade

- **Patch**: `third_party/patches/rules_jvm_external_coursier_gmm.patch`
- Upgrades Coursier v2.1.24 → v2.1.25-M23 (has GMM support via `--enable-modules`)

### 2. Variant Syntax Discovery

- **CRITICAL**: `--variant` flags require SPACE-SEPARATED syntax, NOT equals:
  - ✓ `"--variant", "org.gradle.usage=runtime"` (two list elements)
  - ✗ `"--variant=org.gradle.usage=runtime"` (fails with colon-counting bug)
- Source: `coursier/modules/cli/src/main/scala/coursier/cli/resolve/Resolve.scala:341`

### 3. Deduplication Fix

- **Patch**: `third_party/patches/rules_jvm_external_coursier_options_dedup.patch`
- `_merge_repo_lists` in `maven.bzl` was deduplicating `--variant` flags (breaking repeated flags)
- Fix: Handle `additional_coursier_options` separately without deduplication

### 4. AnyOf Matcher Syntax

- For fallback values: `"org.gradle.dependency.bundling=external|shadowed"`
- Prefers first value, falls back to second
- Source: `coursier/modules/core/shared/src/main/scala/coursier/core/VariantSelector.scala:227-254`

### 5. Missing File Key Fix

- **Patches**: `rules_jvm_external_artifact_file_key_1.patch` and `rules_jvm_external_artifact_file_key_2.patch`
- GMM can produce artifacts without a "file" key
- Changed `artifact["file"]` to `artifact.get("file")` in two locations

### 6. Path Relativization Fix (SOLVED)

- **Patch**: `third_party/patches/rules_jvm_external_path_relativize.patch`
- In pinned mode, COURSIER_CACHE is set to absolute path, causing absolute paths in BUILD files
- Added else clause at line 1145 to relativize paths containing `/v1/`
- Extracts `v1/https/...` relative path from absolute `/private/var/tmp/.../v1/https/...` path

---

## Fixed Bug: Absolute Paths in BUILD File

### Symptom

```
ERROR: invalid label '/private/var/tmp/.../v1/https/repo1.maven.org/maven2/.../artifact.jar'
       target names may not start with '/'
```

### Root Cause

In `coursier.bzl`, when running in "pinned" mode (`_is_unpinned() == False`):

1. **Line 940**: `COURSIER_CACHE` is set to `str(repository_ctx.path("v1"))` = absolute path
2. **Line 941**: `--cache v1` tells Coursier to use local `v1/` directory
3. Coursier outputs absolute paths like `/private/var/tmp/.../external/.../v1/https/.../artifact.jar`
4. **Line 1145**: `if _is_unpinned(repository_ctx):` - relativization ONLY happens in unpinned case
5. In pinned case, absolute paths flow through to BUILD file unchanged

### Evidence

```bash
# dep-tree.json shows absolute paths:
cat .../external/rules_jvm_external++maven+maven/dep-tree.json | jq '.dependencies[0].file'
# Returns: "/private/var/tmp/.../v1/https/maven.google.com/.../annotation-jvm-1.8.0.jar"
```

### The Fix Needed

Add path relativization for the pinned case. The file paths contain `v1/https/...` which is correct - we just need to
strip the absolute prefix.

**Location**: `private/rules/coursier.bzl` around line 1145

**Approach**: After the unpinned relativization block, add handling for pinned case:

```python
if _is_unpinned(repository_ctx):
    artifact.update({"file": _relativize_and_symlink_file_in_coursier_cache(...)})
else:
    # Pinned case: paths are already in v1/, just need to be relative
    file_path = artifact["file"]
    if "/v1/" in file_path:
        # Extract relative path starting from v1/
        relative_path = "v1/" + file_path.split("/v1/")[1]
        artifact.update({"file": relative_path})
```

---

## Files Modified

### MODULE.bazel

- `single_version_override` for `rules_jvm_external` with 4 patches
- Compose Desktop artifacts with `-desktop`/`-jvm` suffixes
- GMM options in `additional_coursier_options` (currently commented out for testing)
- `fail_on_missing_checksum = False` for Google Maven

### Patches in third_party/patches/

1. `rules_jvm_external_coursier_gmm.patch` - Coursier upgrade
2. `rules_jvm_external_coursier_options_dedup.patch` - Fix dedup of --variant flags
3. `rules_jvm_external_artifact_file_key_1.patch` - Fix artifact["file"] in artifact_utilities.bzl
4. `rules_jvm_external_artifact_file_key_2.patch` - Fix artifact["file"] in coursier.bzl (2 locations)
5. `rules_jvm_external_path_relativize.patch` - Fix absolute paths in pinned mode

### BUILD.bazel in third_party/patches/

- Exports all patch files

---

## Relevant Source Locations

### rules_jvm_external v6.9

- `private/rules/coursier.bzl:1145` - Where relativization happens (only for unpinned)
- `private/rules/coursier.bzl:934-945` - Where COURSIER_CACHE is set (only for pinned)
- `private/rules/coursier.bzl:218-244` - `_relativize_and_symlink_file_in_coursier_cache`
- `private/artifact_utilities.bzl:36` - `artifact["file"]` access
- `private/dependency_tree_parser.bzl:103` - Where file paths go to BUILD

### Coursier Source (at /Users/adsc/dev/refs/coursier/)

- `modules/core/shared/src/main/scala/coursier/core/VariantSelector.scala` - Variant matching
- `modules/cli/src/main/scala/coursier/cli/resolve/Resolve.scala` - CLI parsing

### Fork at /Users/adsc/dev/forks/fork-rules_jvm_external/

- Use for reference but patches must target v6.9 release (different line numbers!)

---

## Creating the Path Relativization Patch

### Step 1: Get exact context from v6.9

```bash
curl -sL "https://raw.githubusercontent.com/bazel-contrib/rules_jvm_external/6.9/private/rules/coursier.bzl" | sed -n '1143,1165p'
```

### Step 2: The fix logic

After line 1146 (`if _is_unpinned...` block), add else clause for pinned case:

```python
else:
    # Pinned case: Coursier downloaded to local v1/ cache with --cache v1
    # but COURSIER_CACHE was set to absolute path, so JSON has absolute paths.
    # Extract the relative path starting from v1/
    file_path = artifact["file"]
    if file_path and "/v1/" in file_path:
        relative_path = "v1/" + file_path.split("/v1/", 1)[1]
        artifact.update({"file": relative_path})
```

### Step 3: Create patch file

Save to `third_party/patches/rules_jvm_external_path_relativize.patch`

### Step 4: Update BUILD.bazel and MODULE.bazel

Add the new patch to both files.

---

## GMM Variant Attributes for JVM Desktop

```starlark
additional_coursier_options = [
    "--enable-modules",
    "--variant", "org.gradle.category=library",
    "--variant", "org.gradle.usage=runtime",
    "--variant", "org.gradle.jvm.environment=standard-jvm",
    "--variant", "org.jetbrains.kotlin.platform.type=jvm",
    "--variant", "org.gradle.libraryelements=jar",
    "--variant", "org.gradle.dependency.bundling=external|shadowed",
    "--variant", "ui=awt",  # skiko AWT vs Android
],
```

---

## Test Commands

```bash
# Test maven resolution
bazel build @maven//:pin

# Check raw Coursier output
cat /private/var/tmp/_bazel_adsc/.../external/rules_jvm_external++maven+maven/dep-tree.json | jq '.dependencies[0]'

# Check generated BUILD
head -100 /private/var/tmp/_bazel_adsc/.../external/rules_jvm_external++maven+maven/BUILD

# Verify patches apply to v6.9
curl -sL "https://raw.githubusercontent.com/bazel-contrib/rules_jvm_external/6.9/private/rules/coursier.bzl" -o /tmp/coursier_v69.bzl
patch --dry-run -p1 < patch_file.patch
```

---

## Key Insights

1. **GMM works!** The variant selection correctly picks JVM Desktop variants
2. **Path issue is NOT GMM-specific** - it's a pre-existing bug exposed by our patches
3. **Pinned vs Unpinned**: bzlmod uses "pinned" mode where Coursier downloads to local cache
4. **The symlinks exist** - `v1/https/.../artifact.jar` symlinks are created correctly
5. **Just need relative paths** - The paths have correct structure, just absolute prefix

---

## Next Steps

1. ✅ Create `rules_jvm_external_path_relativize.patch` with the else clause fix
2. ✅ Add patch to BUILD.bazel exports
3. ✅ Add patch to MODULE.bazel single_version_override
4. ✅ GMM options already active in MODULE.bazel
5. ✅ Test with `bazel build @maven//:pin` - PASSED
6. ✅ Create `rules_jvm_external_filter_kmp_common.patch` - filters kotlin-stdlib-common and kotlinx common modules
7. ⚠️ Compose Desktop build blocked by Coursier cycle issue (see below)

## Remaining Issue: Coursier Cycle in kotlinx-serialization

Coursier GMM resolution creates a cycle:

```
kotlinx-serialization-json-jvm → kotlinx-serialization-json-io-jvm → kotlinx-serialization-json-jvm
```

**Root cause**: Coursier adds `json-jvm` as a dependency of `json-io-jvm` during resolution, even though the GMM
metadata only declares dependency on `json` (common module).

**Workarounds**:

1. Remove kotlinx-serialization-json from direct dependencies (only use core)
2. Wait for Coursier fix upstream
3. Create cycle-detection patch for rules_jvm_external

---

## Reference PRs and Issues

- https://github.com/coursier/coursier/pull/3320 - GMM variant matcher
- https://github.com/coursier/coursier/pull/3269 - Variant ADTs
- https://github.com/bazel-contrib/rules_jvm_external/issues/909 - GMM for KMP
- https://github.com/bazel-contrib/rules_jvm_external/issues/864 - Resolving KMP via GMM

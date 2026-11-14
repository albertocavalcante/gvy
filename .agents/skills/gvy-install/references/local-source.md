<!--
SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
SPDX-License-Identifier: Apache-2.0
-->

# Preserve local source for a build

If the requested source is already in an owned, isolated worktree, build there. For a protected or shared checkout,
preserve its requested HEAD and changes in an owned worktree before building. Follow the repository's worktree rules;
keep the new checkout in the owner's persistent worktree location.

Record the source HEAD and status before copying. Capture its tracked changes in a private temporary directory:

```sh
git -C "$source" rev-parse HEAD
git -C "$source" status --short
git -C "$source" diff --binary HEAD > "$private_dir/changes.patch"
```

Here, `source` is the requested checkout, `private_dir` is an owned private temporary directory, and `target` below is
the newly created worktree at that recorded HEAD. Apply the patch after checking it:

```sh
git -C "$target" apply --check "$private_dir/changes.patch"
git -C "$target" apply "$private_dir/changes.patch"
```

For an empty patch, skip the apply commands. The patch includes staged and unstaged tracked changes together. Inventory
untracked files with `git ls-files --others --exclude-standard`; copy only the requested source inputs, preserving their
bytes and modes. Do not blindly copy credentials, caches, or build outputs. Verify the target's tracked diff and the
selected untracked inputs against the captured source before building. If the source changed during capture, recapture
or stop with the discrepancy recorded.

Do not switch, reset, clean, or stash the shared checkout. Initialize the target's build environment and follow the
host's build admission controls before running the source installer. A clean main build does not satisfy a request to
build local changes. Keep failure evidence that recovery needs; remove owned temporary capture files and the worktree
when their work is finished.

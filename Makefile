# Compatibility entry points. New commands live in Justfile.
# Bootstrap Just: node tools/setup-just.js --bin-dir "$HOME/.local/bin"
GRADLE_ARGS ?=
SCENARIO ?=
VERSION ?=
.DEFAULT_GOAL := help

GVY_GRADLE_TARGETS := build jar rebuild test retest clean lint format fix-imports quality version e2e viz-run bazel-build bazel-test bazel-lint bazel-clean bazel-jar bazel-sync
GVY_COMPAT_TARGETS := ext-install ext-build ext-test ext-package ext-vscode ext-cursor ext-agy ext-kiro ext-windsurf ext-editors viz-install release release-build verify-builds ext-vscode-source ext-package-source ext-doctor
.PHONY: help $(GVY_GRADLE_TARGETS) $(GVY_COMPAT_TARGETS) e2e-single release-publish run-stdio run-socket

help:
	@just --list --unsorted

$(GVY_GRADLE_TARGETS):
	@just "$@" $(GRADLE_ARGS)

$(GVY_COMPAT_TARGETS):
	@just "$@"

e2e-single:
	@just e2e-single "$(SCENARIO)" $(GRADLE_ARGS)

release-publish:
	@just release-publish "$(VERSION)"

# Keep build flags on the prerequisite, separate from server runtime arguments.
run-stdio run-socket:
	@just jar $(GRADLE_ARGS)
	@just --no-deps "$@"

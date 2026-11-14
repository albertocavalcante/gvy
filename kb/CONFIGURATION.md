# Effective editor settings

Initialization options and `workspace/didChangeConfiguration` accept the same flat setting keys. A settings object is a
complete snapshot: omitted keys use server defaults, and `{}` resets to defaults. Non-object configuration notifications
are ignored.

The following settings take effect without restarting the server:

- `groovy.diagnostics.providers.enabled` and `.disabled` select diagnostic providers, including `parser`.
- `groovy.codenarc.enabled` controls the default enablement of CodeNarc. Explicit provider overrides retain their
  precedence.
- `groovy.diagnostics.rules.*` configure the custom rule provider.
- `groovy.server.maxNumberOfProblems` caps each diagnostic publication and `diagnose` result. Parser diagnostics precede
  additional provider diagnostics. Zero disables reported problems; negative values are treated as zero.
- `groovy.mode` selects `groovy`, `jenkins`, or `auto` for shared completion and Native Jenkins hover strategies.
  Explicit mode overrides file detection. Hover always uses the Native engine; the retired Core hover engine never
  provided Jenkins step metadata.
- `groovy.compilation.cacheMaxEntries` resizes the compilation cache.

Diagnostic settings refresh open documents and cancel pending work under the previous configuration. Results from
replaced providers are not published after replacement. Providers are constructed from the new configuration; no
client-side configuration file is required by these settings.

Other settings are not guaranteed to reload dynamically. Restart after changing the parser engine, build resolution
strategy, Java home, worker descriptors, or workspace compilation policy. These changes require a new workspace or
engine lifecycle and are outside this runtime settings contract.

// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

export const pages = [
  ['docs/overview.md', 'overview/index', 'Overview'],
  ['docs/quality-bar.md', 'overview/roadmap', 'Roadmap'],
  ['docs/lsp/usage.md', 'lsp/usage', 'Language server usage'],
  ['docs/lsp/feature-support.md', 'lsp/feature-support', 'Feature support'],
  ['docs/installation.md', 'getting-started/installation', 'Installation'],
  ['docs/user/semanticdb.md', 'architecture/semanticdb', 'SemanticDB'],
  ...['quickstart', 'groovy', 'gradle', 'jenkins', 'spock', 'java', 'diagnostics-formatting', 'private-environments', 'troubleshooting', 'contributing'].map(name => [`docs/user/${name}.md`, `guides/${name}`, name.replaceAll('-', ' ')]),
  ['docs/user/reference-settings.md', 'reference/settings', 'Settings reference'],
  ['docs/user/reference-commands.md', 'reference/commands', 'Commands reference'],
];

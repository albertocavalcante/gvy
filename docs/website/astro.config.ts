// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import starlight from "@astrojs/starlight";
import { defineConfig } from "astro/config";
import { focusableTables } from "./src/components/scrollable-tables.mjs";
import starlightLinksValidator from "starlight-links-validator";

export default defineConfig({
  site: "https://albertocavalcante.github.io",
  base: "/gvy",
  integrations: [
    focusableTables(),
    starlight({
      plugins: [starlightLinksValidator()],
      disable404Route: true,
      title: "gvy",
      description: "Groovy language tools for your editor.",
      social: [
        {
          icon: "github",
          label: "GitHub",
          href: "https://github.com/albertocavalcante/gvy",
        },
      ],
      editLink: {
        baseUrl:
          "https://github.com/albertocavalcante/gvy/edit/main/docs/website/",
      },
      lastUpdated: true,
      customCss: ["./src/styles/custom.css"],
      components: { Search: "./src/components/Search.astro" },
      expressiveCode: {
        plugins: [
          {
            name: "focusable-code-blocks",
            hooks: {
              postprocessRenderedBlock({ renderData }) {
                const pre = renderData.blockAst.children.find(
                  (node) => node.type === "element" && node.tagName === "pre",
                );
                if (pre?.type === "element") pre.properties.tabIndex = 0;
              },
            },
          },
        ],
      },
      sidebar: [
        {
          label: "Get started",
          items: [
            { label: "Installation", slug: "getting-started/installation" },
            { label: "Quickstart", slug: "guides/quickstart" },
          ],
        },
        {
          label: "Workflows",
          items: [
            { label: "Groovy", slug: "guides/groovy" },
            { label: "Gradle", slug: "guides/gradle" },
            { label: "Jenkins", slug: "guides/jenkins" },
            { label: "Spock", slug: "guides/spock" },
          ],
        },
        {
          label: "Configure",
          items: [
            { label: "Java", slug: "guides/java" },
            {
              label: "Diagnostics and formatting",
              slug: "guides/diagnostics-formatting",
            },
            {
              label: "Private environments",
              slug: "guides/private-environments",
            },
            { label: "Other LSP clients", slug: "lsp/usage" },
          ],
        },
        {
          label: "Reference",
          items: [
            { label: "Settings", slug: "reference/settings" },
            { label: "Commands", slug: "reference/commands" },
            { label: "Feature support", slug: "lsp/feature-support" },
            { label: "Module overview", slug: "overview" },
            { label: "Quality bar", slug: "overview/roadmap" },
            { label: "SemanticDB", slug: "architecture/semanticdb" },
          ],
        },
        {
          label: "Troubleshoot",
          items: [{ label: "Troubleshooting", slug: "guides/troubleshooting" }],
        },
        {
          label: "Contribute",
          items: [{ label: "Contributing", slug: "guides/contributing" }],
        },
      ],
    }),
  ],
});

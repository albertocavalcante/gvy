// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from 'astro/config';
import mdx from '@astrojs/mdx';

// Extend the configured Satteri processor, as the production site does.
const focusableRegions = {
  name: 'focusable-regions',
  hooks: {
    'astro:config:setup': ({ config }) => {
      const processor = config.markdown.processor;
      if (processor.name !== 'satteri') throw new Error('Expected Satteri');
      processor.options.hastPlugins.push({
        name: 'focusable-regions',
        element: {
          filter: ['table', 'pre'],
          visit(node, context) { context.setProperty(node, 'tabIndex', 0); },
        },
      });
    },
  },
};
export default defineConfig({
  base: '/gvy', site: 'https://albertocavalcante.github.io', integrations: [focusableRegions, mdx()],
  markdown: { shikiConfig: { transformers: [{ name: 'focusable-code', pre(node) { node.properties.tabIndex = 0; } }] } },
});

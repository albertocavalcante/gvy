// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from 'astro/config';
import mdx from '@astrojs/mdx';
import remarkGfm from 'remark-gfm';
export default defineConfig({ base: '/gvy', site: 'https://albertocavalcante.github.io', integrations: [mdx()], markdown: { remarkPlugins: [remarkGfm] } });

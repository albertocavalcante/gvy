// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import { mdsvex } from 'mdsvex';
import remarkGfm from 'remark-gfm';
import rehypeSlug from 'rehype-slug';
import { defineConfig } from 'vite';
export default defineConfig({ plugins: [sveltekit({
  adapter: adapter(), paths: { base: '/gvy' },
  extensions: ['.svelte', '.svx'], preprocess: [mdsvex({ extensions: ['.svx'], remarkPlugins: [remarkGfm], rehypePlugins: [rehypeSlug] })]
})] });

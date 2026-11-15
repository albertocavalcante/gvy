<!-- SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
SPDX-License-Identifier: Apache-2.0 -->

<script>
  import '../../../shared/style.css';
  import { onMount } from 'svelte';
  import { page } from '$app/state';
  let { children } = $props();
  onMount(() => { import('../../../shared/search.js'); });
</script>
<svelte:head><title>gvy documentation experiment</title></svelte:head>
<a href="#main">Skip to content</a><header><nav aria-label="Main navigation"><a href="/gvy/">Home</a> <a href="/gvy/guides/quickstart/">Quickstart</a> <a href="/gvy/reference/settings/">Settings</a></nav></header>
<aside aria-label="Experiment notice">This is a four-page renderer experiment, not the published documentation.</aside>
<form id="search" hidden><label>Search <input name="query" required /></label> <button>Search</button></form><ul id="results" aria-live="polite"></ul>
<main id="main" data-pagefind-body>{@render children()}</main>
{#if page.url.pathname.includes('/guides/quickstart/')}<footer><a href="https://github.com/albertocavalcante/gvy/edit/main/docs/user/quickstart.md">Edit this page</a></footer>{/if}
{#if page.url.pathname.includes('/reference/settings/')}<footer><a href="https://github.com/albertocavalcante/gvy/edit/main/docs/user/reference-settings.md">Edit this page</a></footer>{/if}

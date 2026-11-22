// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
const form = document.querySelector('#search');
if (form) {
  form.hidden = false;
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const results = document.querySelector('#results');
    results.textContent = 'Searching…';
    try {
      const indexUrl = '/gvy/pagefind/pagefind.js';
      const pagefind = await import(/* @vite-ignore */ indexUrl);
      const response = await pagefind.search(new FormData(form).get('query'));
      results.replaceChildren();
      for (const result of response.results.slice(0, 10)) {
        const data = await result.data();
        const item = document.createElement('li');
        const link = document.createElement('a');
        link.href = data.url.startsWith('/gvy/') ? data.url : `/gvy/${data.url.replace(/^\//, '')}`;
        link.textContent = data.meta.title || data.url;
        item.append(link);
        results.append(item);
      }
      if (!response.results.length) results.textContent = 'No results';
    } catch {
      results.textContent = 'Search unavailable';
    }
  });
}
